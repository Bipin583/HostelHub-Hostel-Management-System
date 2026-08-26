package com.hostelops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.HostelType;
import com.hostelops.support.AbstractPostgresIT;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The proof that room allocation is race-free.
 *
 * <p>Everything else about allocation is covered by unit tests with mocked
 * repositories. Those cannot prove this property: a mock has no locks, no
 * isolation level and no constraints, so it will happily let two callers take the
 * same last bed. This class fires genuinely simultaneous HTTP requests at a real
 * Postgres and counts what survives.
 *
 * <h2>What is deliberately not stubbed</h2>
 *
 * <p>Requests go in over HTTP through the real filter chain, the real role gate,
 * the real service transaction and the real {@code GlobalExceptionHandler}. The
 * only shortcut is that access tokens are minted directly instead of by posting a
 * password, which keeps a working credential out of the repository and changes
 * nothing about the path under test.
 *
 * <h2>How the race is actually forced</h2>
 *
 * <p>Every worker thread parks on a shared start gate and is released at once, so
 * the requests arrive inside the same few milliseconds rather than in a loop where
 * each has already finished before the next begins. A sequential loop would pass
 * against completely broken locking, which is the usual way this test gets written
 * and the usual reason it proves nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AllocationConcurrencyIT extends AbstractPostgresIT {

    private static final String ALLOCATIONS = "/api/v1/warden/allocations";
    private static final String AUTO_ALLOCATIONS = "/api/v1/warden/allocations/auto";

    /**
     * Year 5 is the scratch cohort for auto-allocation tests.
     *
     * <p>{@code V2__room_reference_data.sql} seeds floors 1 to 4 only, so no
     * reference room is eligible for year 5. Auto-allocation searches by cohort
     * rather than by an id the test chooses, so its candidate set has to be
     * controlled; a year nothing else uses is how that is done.
     */
    private static final int SCRATCH_YEAR = 5;

    @Autowired
    private TestRestTemplate rest;

    // ------------------------------------------------------------------
    // The headline case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("twelve requests race for three beds: exactly three win, nine get a clean 409 ROOM_FULL")
    void exactlyCapacitySucceedsWhenEveryoneRacesTheSameRoom() throws Exception {
        int capacity = 3;
        int contenders = 12;

        SeededUser warden = seedWarden(HostelScope.LH);
        String token = accessTokenFor(warden, HostelScope.LH);
        SeededRoom room = seedRoom(HostelType.LH, 2, capacity);

        List<SeededStudent> students = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            students.add(seedStudent(Gender.F, 2));
        }

        List<ResponseEntity<String>> responses = simultaneously(contenders,
                index -> allocate(token, students.get(index).studentId(), room.roomId()));

        // 1. The bed count is the invariant. Anything above capacity means two
        //    students were handed the same bed.
        assertThat(activeAllocationCount(room.roomId()))
                .as("active allocations in a room of capacity %d", capacity)
                .isEqualTo(capacity);

        // 2. And the API agreed with the database about who won.
        assertThat(statusCount(responses, HttpStatus.CREATED)).isEqualTo(capacity);
        assertThat(statusCount(responses, HttpStatus.CONFLICT)).isEqualTo(contenders - capacity);

        // 3. Losing a race is not an error. Every loser gets the domain code for
        //    "no beds left", never a 500 and never a leaked constraint name.
        assertThat(responses).noneMatch(response -> response.getStatusCode().is5xxServerError());
        responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .forEach(response -> {
                    assertThat(errorCodeOf(response)).isEqualTo("ROOM_FULL");
                    assertThat(response.getBody()).doesNotContain("trg_", "uq_", "SQLState", "org.postgresql");
                });

        // 4. The student rows agree with the allocation rows. A student marked
        //    ALLOCATED without an allocation is the exact drift the predecessor's
        //    Student.allocated_room column produced.
        long markedAllocated = students.stream()
                .filter(student -> "ALLOCATED".equals(allocationStatusOf(student.studentId())))
                .count();
        assertThat(markedAllocated).isEqualTo(capacity);
    }

    @Test
    @DisplayName("a single free bed under load goes to exactly one of twenty callers")
    void theLastBedIsIndivisible() throws Exception {
        int contenders = 20;

        SeededUser warden = seedWarden(HostelScope.LH);
        String token = accessTokenFor(warden, HostelScope.LH);
        // Capacity 1 removes every degree of freedom: the correct answer is one
        // success, and any locking bug at all produces two.
        SeededRoom room = seedRoom(HostelType.LH, 3, 1);

        List<SeededStudent> students = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            students.add(seedStudent(Gender.F, 3));
        }

        List<ResponseEntity<String>> responses = simultaneously(contenders,
                index -> allocate(token, students.get(index).studentId(), room.roomId()));

        assertThat(activeAllocationCount(room.roomId())).isEqualTo(1);
        assertThat(statusCount(responses, HttpStatus.CREATED)).isEqualTo(1);
        assertThat(statusCount(responses, HttpStatus.CONFLICT)).isEqualTo(contenders - 1);
    }

    @Test
    @DisplayName("one student submitting six times at once ends up with one bed, not six")
    void aStudentCannotRaceThemselvesIntoTwoRooms() throws Exception {
        int attempts = 6;

        SeededUser warden = seedWarden(HostelScope.LH);
        String token = accessTokenFor(warden, HostelScope.LH);
        // Room capacity comfortably exceeds the number of attempts, so ROOM_FULL
        // cannot be what stops the duplicates -- only the per-student guard can.
        SeededRoom room = seedRoom(HostelType.LH, 2, attempts + 2);
        SeededStudent student = seedStudent(Gender.F, 2);

        List<ResponseEntity<String>> responses = simultaneously(attempts,
                index -> allocate(token, student.studentId(), room.roomId()));

        // The service's own "already allocated?" check runs before the lock, so
        // several requests legitimately pass it. What stops them is the partial
        // unique index uq_allocations_active_student, which the handler maps back
        // to the same domain code -- so the client sees one story either way.
        assertThat(activeAllocationCountForStudent(student.studentId())).isEqualTo(1);
        assertThat(statusCount(responses, HttpStatus.CREATED)).isEqualTo(1);
        assertThat(statusCount(responses, HttpStatus.CONFLICT)).isEqualTo(attempts - 1);
        responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .forEach(response -> assertThat(errorCodeOf(response))
                        .isEqualTo("STUDENT_ALREADY_ALLOCATED"));
    }

    // ------------------------------------------------------------------
    // Auto-allocation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("auto-allocation into one eligible room stops at capacity")
    void autoAllocationRespectsCapacity() throws Exception {
        resetScratchCohort();

        int capacity = 3;
        int contenders = 10;

        SeededUser warden = seedWarden(HostelScope.LH);
        String token = accessTokenFor(warden, HostelScope.LH);
        SeededRoom room = seedRoom(HostelType.LH, SCRATCH_YEAR, capacity);

        List<SeededStudent> students = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            students.add(seedStudent(Gender.F, SCRATCH_YEAR));
        }

        List<ResponseEntity<String>> responses = simultaneously(contenders,
                index -> autoAllocate(token, students.get(index).studentId()));

        assertThat(activeAllocationCount(room.roomId())).isEqualTo(capacity);
        assertThat(statusCount(responses, HttpStatus.CREATED)).isEqualTo(capacity);
        // Nothing eligible was left, which is a different fact from "the room you
        // named is full", and gets its own code so a client can tell them apart.
        responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .forEach(response -> assertThat(errorCodeOf(response)).isEqualTo("NO_ROOM_AVAILABLE"));
    }

    @Test
    @DisplayName("auto-allocation across several rooms overfills none of them")
    void autoAllocationNeverOverfillsAnyRoom() throws Exception {
        resetScratchCohort();

        int roomCount = 4;
        int capacity = 2;
        int contenders = 14;

        SeededUser warden = seedWarden(HostelScope.MH);
        String token = accessTokenFor(warden, HostelScope.MH);

        List<SeededRoom> rooms = new ArrayList<>();
        for (int i = 0; i < roomCount; i++) {
            rooms.add(seedRoom(i % 2 == 0 ? HostelType.BH : HostelType.MH, SCRATCH_YEAR, capacity));
        }

        List<SeededStudent> students = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            students.add(seedStudent(Gender.M, SCRATCH_YEAR));
        }

        List<ResponseEntity<String>> responses = simultaneously(contenders,
                index -> autoAllocate(token, students.get(index).studentId()));

        // Per-room capacity is the invariant that must hold absolutely.
        for (SeededRoom room : rooms) {
            assertThat(activeAllocationCount(room.roomId()))
                    .as("occupancy of room %s", room.roomName())
                    .isBetween(0L, (long) capacity);
        }
        for (SeededStudent student : students) {
            assertThat(activeAllocationCountForStudent(student.studentId()))
                    .as("beds held by student %d", student.studentId())
                    .isLessThanOrEqualTo(1L);
        }

        long occupied = rooms.stream().mapToLong(room -> activeAllocationCount(room.roomId())).sum();
        assertThat(statusCount(responses, HttpStatus.CREATED)).isEqualTo((int) occupied);
        assertThat(occupied).isPositive().isLessThanOrEqualTo((long) roomCount * capacity);

        // The exact number of winners is deliberately not asserted. Each
        // transaction walks its candidate list in an order derived from live
        // occupancy, so two transactions can lock two rooms in opposite orders
        // and deadlock. Postgres detects that and aborts one; the handler turns
        // it into a retryable 409. Pinning an exact count here would make the
        // test flaky about something that is correct behaviour -- so what is
        // asserted instead is that no request ever gets a 500 and no room is
        // ever overfilled.
        assertThat(responses).noneMatch(response -> response.getStatusCode().is5xxServerError());
        responses.stream()
                .filter(response -> response.getStatusCode() != HttpStatus.CREATED)
                .forEach(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(errorCodeOf(response)).isIn(
                            "NO_ROOM_AVAILABLE", "ROOM_FULL", "STUDENT_ALREADY_ALLOCATED", "CONFLICT");
                });
    }

    // ------------------------------------------------------------------
    // Defence in depth
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the database refuses an overfill that never went through the service")
    void theCapacityTriggerCatchesWritesThatBypassTheService() throws Exception {
        SeededUser warden = seedWarden(HostelScope.LH);
        String token = accessTokenFor(warden, HostelScope.LH);
        SeededRoom room = seedRoom(HostelType.LH, 1, 1);
        SeededStudent occupant = seedStudent(Gender.F, 1);
        SeededStudent gatecrasher = seedStudent(Gender.F, 1);

        assertThat(allocate(token, occupant.studentId(), room.roomId()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // A hand-applied data fix, a future batch job, an admin console: writes
        // that will never take the room's lock because they never call the
        // service. trg_allocations_capacity is what stands behind those. It is
        // not the primary control -- being a read, it is subject to the same
        // interleaving the lock exists to prevent -- but it closes the paths the
        // service does not own.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO allocations (student_id, room_id, active) VALUES (?, ?, TRUE)",
                gatecrasher.studentId(), room.roomId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("at capacity");

        assertThat(activeAllocationCount(room.roomId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the database refuses a second active allocation for one student")
    void thePartialUniqueIndexCatchesDoubleAllocation() throws Exception {
        SeededUser warden = seedWarden(HostelScope.LH);
        String token = accessTokenFor(warden, HostelScope.LH);
        SeededRoom first = seedRoom(HostelType.LH, 4, 2);
        SeededRoom second = seedRoom(HostelType.LH, 4, 2);
        SeededStudent student = seedStudent(Gender.F, 4);

        assertThat(allocate(token, student.studentId(), first.roomId()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO allocations (student_id, room_id, active) VALUES (?, ?, TRUE)",
                student.studentId(), second.roomId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_allocations_active_student");

        // Vacating leaves the old row in place as history, and the index is
        // partial, so the same student can legitimately be allocated again.
        jdbc.update("""
                UPDATE allocations SET active = FALSE, vacated_at = now()
                WHERE student_id = ? AND active
                """, student.studentId());
        assertThat(allocate(token, student.studentId(), second.roomId()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(activeAllocationCountForStudent(student.studentId())).isEqualTo(1);
        Long historyRows = jdbc.queryForObject(
                "SELECT count(*) FROM allocations WHERE student_id = ?", Long.class, student.studentId());
        assertThat(historyRows).as("history is retained, not overwritten").isEqualTo(2L);
    }

    // ------------------------------------------------------------------
    // harness
    // ------------------------------------------------------------------

    // simultaneously(...), statusCount(...) and errorCodeOf(...) live on
    // AbstractPostgresIT: the payment and reminder proofs need the same start gate,
    // and a harness copied per class is one that drifts.

    private ResponseEntity<String> allocate(String token, long studentId, long roomId) {
        String body = "{\"studentId\":%d,\"roomId\":%d}".formatted(studentId, roomId);
        return rest.exchange(ALLOCATIONS, HttpMethod.POST,
                new HttpEntity<>(body, jsonWithToken(token)), String.class);
    }

    private ResponseEntity<String> autoAllocate(String token, long studentId) {
        String body = "{\"studentId\":%d}".formatted(studentId);
        return rest.exchange(AUTO_ALLOCATIONS, HttpMethod.POST,
                new HttpEntity<>(body, jsonWithToken(token)), String.class);
    }

    /**
     * Empties the year-5 cohort so an auto-allocation test sees only its own rooms.
     *
     * <p>Auto-allocation picks a room by searching, not by an id the caller
     * supplies, so leftovers from a previous test would widen the candidate set and
     * change the answer. Allocations go first because
     * {@code fk_allocations_room} is {@code ON DELETE RESTRICT} -- deliberately, so
     * that deleting an occupied room in production fails loudly.
     */
    private void resetScratchCohort() {
        jdbc.update("""
                DELETE FROM allocations
                WHERE room_id IN (SELECT id FROM rooms WHERE eligible_year = ?)
                """, SCRATCH_YEAR);
        jdbc.update("DELETE FROM rooms WHERE eligible_year = ?", SCRATCH_YEAR);
    }
}
