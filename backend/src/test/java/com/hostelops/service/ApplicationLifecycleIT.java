package com.hostelops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hostelops.domain.AllocationStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.HostelType;
import com.hostelops.support.AbstractPostgresIT;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The accommodation lifecycle end to end: apply, decide, allocate, and the
 * rollback when the decision cannot be honoured.
 *
 * <p>{@code ApplicationServiceTest} already proves the service calls the
 * allocation path and lets its exception escape. What it cannot prove is the
 * consequence, because a unit test has no transaction: it asserts that
 * {@code approve} threw, not that the approval is absent from the database
 * afterwards. Those are different claims, and only the second one matters. An
 * approval that survives a failed allocation produces a student who has been
 * told they have a room and has no bed -- the worst possible outcome for this
 * feature, and one that looks completely fine from the warden's screen.
 *
 * <p>So the central test here is {@code approvalRollsBackWhenNothingIsAvailable}:
 * a 409 comes back, and the application is still {@code PENDING}, and the
 * student is still {@code PENDING}, and no allocation row exists. Everything
 * else in this class is the happy path that makes that test meaningful.
 *
 * <p>Year 5 is used for the no-room case. The room inventory in
 * {@code V2__room_reference_data.sql} covers years 1 to 4 -- one floor per year
 * -- so a fifth-year student has no eligible room anywhere in the building and
 * the failure is a property of the data rather than of a fixture that some other
 * test could fill up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationLifecycleIT extends AbstractPostgresIT {

    private static final String APPLY = "/api/v1/student/applications";
    private static final String MY_APPLICATIONS = "/api/v1/student/applications";
    private static final String WARDEN_APPLICATIONS = "/api/v1/warden/applications";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper json;

    @Nested
    @DisplayName("applying")
    class Applying {

        @Test
        @DisplayName("a student moves from NOT_APPLIED to PENDING")
        void applyingCreatesAPendingApplication() {
            SeededStudent student = seedStudent(Gender.F, 2);
            assertThat(allocationStateOf(student.studentId())).isEqualTo(AllocationStatus.NOT_APPLIED);

            ResponseEntity<String> response = apply(student);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(body(response).path("status").asText()).isEqualTo("PENDING");
            assertThat(body(response).path("rollNumber").asText()).isEqualTo(student.rollNumber());
            assertThat(body(response).path("decidedAt").isNull()).isTrue();
            assertThat(allocationStateOf(student.studentId())).isEqualTo(AllocationStatus.PENDING);
        }

        @Test
        @DisplayName("a second application while one is pending is a 409, enforced by a partial unique index")
        void duplicateApplicationIsRejected() {
            SeededStudent student = seedStudent(Gender.F, 2);
            apply(student);

            ResponseEntity<String> second = apply(student);

            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCodeOf(second)).isEqualTo("DUPLICATE_APPLICATION");
            // The service checks the student's status, and uq_applications_one_pending
            // stands behind it for the case where two requests check at the same moment.
            assertThat(pendingApplicationCount(student.studentId())).isEqualTo(1);
        }

        @Test
        @DisplayName("a warden hitting the student route is forbidden, not routed to a student record")
        void nonStudentCannotApply() {
            SeededUser warden = seedWarden(HostelScope.LH);

            ResponseEntity<String> response = rest.exchange(APPLY, HttpMethod.POST,
                    new HttpEntity<>(jsonWithToken(accessTokenFor(warden, HostelScope.LH))), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("a student sees their own application history and nobody else's")
        void historyIsScopedToTheCaller() {
            SeededStudent mine = seedStudent(Gender.F, 2);
            SeededStudent theirs = seedStudent(Gender.F, 2);
            apply(mine);
            apply(theirs);

            ResponseEntity<String> response = rest.exchange(MY_APPLICATIONS, HttpMethod.GET,
                    new HttpEntity<>(jsonWithToken(accessTokenForStudent(mine))), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(elements(body(response)))
                    .singleElement()
                    .satisfies(node ->
                            assertThat(node.path("studentId").asLong()).isEqualTo(mine.studentId()));
        }
    }

    @Nested
    @DisplayName("approving")
    class Approving {

        @Test
        @DisplayName("approval allocates a room in the same transaction")
        void approvalAllocates() {
            SeededStudent student = seedStudent(Gender.F, 2);
            long applicationId = applicationIdOf(apply(student));
            SeededUser warden = seedWarden(HostelScope.LH);

            ResponseEntity<String> response = approve(warden, applicationId, "Bed confirmed");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(body(response).path("status").asText()).isEqualTo("APPROVED");
            assertThat(body(response).path("decidedBy").asText()).isEqualTo(displayNameOf(warden));
            assertThat(body(response).path("decidedAt").isNull()).isFalse();
            assertThat(body(response).path("note").asText()).isEqualTo("Bed confirmed");

            // The two facts that must be true together. Either one alone is a bug: a
            // status with no row is a promise with no bed, a row with no status is a
            // student who cannot see the room they were given.
            assertThat(allocationStateOf(student.studentId())).isEqualTo(AllocationStatus.ALLOCATED);
            assertThat(activeAllocationCountForStudent(student.studentId())).isEqualTo(1);
        }

        @Test
        @DisplayName("the allocated room matches the student's gender and year")
        void allocationHonoursTheMatchingRule() {
            SeededStudent student = seedStudent(Gender.F, 3);
            long applicationId = applicationIdOf(apply(student));

            approve(seedWarden(HostelScope.LH), applicationId, null);

            String hostelType = jdbc.queryForObject("""
                    SELECT r.hostel_type FROM allocations a JOIN rooms r ON r.id = a.room_id
                    WHERE a.student_id = ? AND a.active
                    """, String.class, student.studentId());
            Integer eligibleYear = jdbc.queryForObject("""
                    SELECT r.eligible_year FROM allocations a JOIN rooms r ON r.id = a.room_id
                    WHERE a.student_id = ? AND a.active
                    """, Integer.class, student.studentId());

            // A third-year woman lands on the third floor of a ladies' hostel. Getting
            // this wrong is not a subtle failure -- it puts a student in the wrong
            // building.
            assertThat(hostelType).isEqualTo(HostelType.LH.name());
            assertThat(eligibleYear).isEqualTo(3);
        }

        @Test
        @DisplayName("nothing is committed when no eligible room has a free bed")
        void approvalRollsBackWhenNothingIsAvailable() {
            // Year 5: the reference inventory houses years 1-4, so there is no eligible
            // room in the entire database and the outcome does not depend on what any
            // other test has filled.
            SeededStudent finalYear = seedStudent(Gender.F, 5);
            long applicationId = applicationIdOf(apply(finalYear));

            ResponseEntity<String> response = approve(seedWarden(HostelScope.LH), applicationId, "Approved");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCodeOf(response)).isEqualTo("NO_ROOM_AVAILABLE");

            // This is the assertion the whole class is built around. The service wrote
            // the decision to the application, then the allocation failed, and the
            // transaction discarded the decision. A version that caught the exception to
            // "at least record the approval" would leave APPROVED here, with no bed.
            assertThat(applicationStatusOf(applicationId)).isEqualTo("PENDING");
            assertThat(allocationStateOf(finalYear.studentId())).isEqualTo(AllocationStatus.PENDING);
            assertThat(activeAllocationCountForStudent(finalYear.studentId())).isZero();
            assertThat(decidedAtIsNull(applicationId))
                    .as("a rolled-back decision leaves no decision timestamp behind")
                    .isTrue();
        }

        @Test
        @DisplayName("a rolled-back approval can be retried once a bed exists")
        void aFailedApprovalIsRetryable() {
            SeededStudent finalYear = seedStudent(Gender.F, 5);
            long applicationId = applicationIdOf(apply(finalYear));
            SeededUser warden = seedWarden(HostelScope.LH);
            assertThat(approve(warden, applicationId, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            // A fifth-year wing opens.
            seedRoom(HostelType.LH, 5, 2);

            ResponseEntity<String> retry = approve(warden, applicationId, "Retried after the wing opened");

            // Only possible because the first attempt left the application PENDING. Had
            // it been recorded as decided, the retry would be an ILLEGAL_STATE_TRANSITION
            // and the student would need an administrator to unpick the row by hand.
            assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(allocationStateOf(finalYear.studentId())).isEqualTo(AllocationStatus.ALLOCATED);
        }

        @Test
        @DisplayName("the second warden to decide gets a 409, not a silent overwrite")
        void doubleDecisionIsRejected() {
            SeededStudent student = seedStudent(Gender.F, 2);
            long applicationId = applicationIdOf(apply(student));
            SeededUser first = seedWarden(HostelScope.LH);
            SeededUser second = seedWarden(HostelScope.LH);
            approve(first, applicationId, "Approved by the first warden");

            ResponseEntity<String> response = approve(second, applicationId, "Approved again");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCodeOf(response)).isEqualTo("ILLEGAL_STATE_TRANSITION");
            // Still one bed, and still attributed to the warden who actually decided.
            assertThat(activeAllocationCountForStudent(student.studentId())).isEqualTo(1);
            assertThat(decidedByOf(applicationId)).isEqualTo(first.userId());
        }

        @Test
        @DisplayName("a warden cannot decide an application from the other hostel")
        void outOfScopeApplicationIsNotFound() {
            SeededStudent male = seedStudent(Gender.M, 2);
            long applicationId = applicationIdOf(apply(male));
            SeededUser ladiesWarden = seedWarden(HostelScope.LH);

            ResponseEntity<String> response = approve(ladiesWarden, applicationId, null);

            // 404 rather than 403: a 403 would confirm the id is real, and a warden
            // walking the id space would learn the size of the other hostel's intake
            // without reading a single application.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(applicationStatusOf(applicationId)).isEqualTo("PENDING");
            assertThat(activeAllocationCountForStudent(male.studentId())).isZero();
        }

        @Test
        @DisplayName("the pending queue is scoped to the warden's own hostel")
        void pendingQueueIsScoped() {
            SeededStudent female = seedStudent(Gender.F, 2);
            SeededStudent male = seedStudent(Gender.M, 2);
            long mine = applicationIdOf(apply(female));
            long theirs = applicationIdOf(apply(male));
            SeededUser ladiesWarden = seedWarden(HostelScope.LH);

            ResponseEntity<String> queue = rest.exchange(
                    WARDEN_APPLICATIONS + "/pending?size=200", HttpMethod.GET,
                    new HttpEntity<>(jsonWithToken(accessTokenFor(ladiesWarden, HostelScope.LH))),
                    String.class);

            assertThat(queue.getStatusCode()).isEqualTo(HttpStatus.OK);
            // No page of the queue may ever contain a men's-hostel application, whichever
            // page the warden happens to be looking at.
            assertThat(elements(body(queue).path("content"))).allSatisfy(node ->
                    assertThat(node.path("studentId").asLong()).isNotEqualTo(male.studentId()));

            // Asserted by id rather than by searching the page, so the test does not
            // depend on how many applications other tests left in the queue.
            assertThat(fetchApplication(ladiesWarden, mine).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(fetchApplication(ladiesWarden, theirs).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("approval works without a body, because the note is optional")
        void noteIsOptional() {
            SeededStudent student = seedStudent(Gender.F, 1);
            long applicationId = applicationIdOf(apply(student));

            ResponseEntity<String> response = rest.exchange(
                    WARDEN_APPLICATIONS + "/" + applicationId + "/approve", HttpMethod.POST,
                    new HttpEntity<>(jsonWithToken(
                            accessTokenFor(seedWarden(HostelScope.LH), HostelScope.LH))),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(allocationStateOf(student.studentId())).isEqualTo(AllocationStatus.ALLOCATED);
        }
    }

    @Nested
    @DisplayName("rejecting")
    class Rejecting {

        @Test
        @DisplayName("returns the student to NOT_APPLIED so they can apply again")
        void rejectionResetsTheLifecycle() {
            SeededStudent student = seedStudent(Gender.F, 2);
            long applicationId = applicationIdOf(apply(student));

            ResponseEntity<String> response =
                    reject(seedWarden(HostelScope.LH), applicationId, "Documents incomplete");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(body(response).path("status").asText()).isEqualTo("REJECTED");
            assertThat(body(response).path("note").asText()).isEqualTo("Documents incomplete");
            // The rejection belongs to the application, not to the student: there is no
            // REJECTED student status, because a student rejected once must still be
            // housable next term.
            assertThat(allocationStateOf(student.studentId())).isEqualTo(AllocationStatus.NOT_APPLIED);
            assertThat(activeAllocationCountForStudent(student.studentId())).isZero();
        }

        @Test
        @DisplayName("a rejected student can reapply, and the rejection stays on the record")
        void reapplyingIsAllowedAfterRejection() {
            SeededStudent student = seedStudent(Gender.F, 2);
            long firstApplication = applicationIdOf(apply(student));
            reject(seedWarden(HostelScope.LH), firstApplication, "Documents incomplete");

            ResponseEntity<String> second = apply(student);

            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(applicationStatusOf(firstApplication)).isEqualTo("REJECTED");
            assertThat(applicationCount(student.studentId())).isEqualTo(2);
        }

        @Test
        @DisplayName("a rejection without a reason is a 400, because the student has to be told why")
        void reasonIsRequired() {
            SeededStudent student = seedStudent(Gender.F, 2);
            long applicationId = applicationIdOf(apply(student));

            ResponseEntity<String> response =
                    reject(seedWarden(HostelScope.LH), applicationId, "   ");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_FAILED");
            assertThat(applicationStatusOf(applicationId)).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("an already-approved application cannot be rejected afterwards")
        void cannotRejectAnApprovedApplication() {
            SeededStudent student = seedStudent(Gender.F, 2);
            long applicationId = applicationIdOf(apply(student));
            SeededUser warden = seedWarden(HostelScope.LH);
            approve(warden, applicationId, null);

            ResponseEntity<String> response = reject(warden, applicationId, "Changed my mind");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCodeOf(response)).isEqualTo("ILLEGAL_STATE_TRANSITION");
            // The allocation is untouched. Undoing an approval is a vacate operation with
            // its own audit trail, not a second decision on a closed application.
            assertThat(activeAllocationCountForStudent(student.studentId())).isEqualTo(1);
        }
    }

    // ---- helpers ----

    private ResponseEntity<String> apply(SeededStudent student) {
        return rest.exchange(APPLY, HttpMethod.POST,
                new HttpEntity<>(jsonWithToken(accessTokenForStudent(student))), String.class);
    }

    private ResponseEntity<String> approve(SeededUser warden, long applicationId, String note) {
        String body = note == null
                ? "{}"
                : json.createObjectNode().put("note", note).toString();
        return rest.exchange(WARDEN_APPLICATIONS + "/" + applicationId + "/approve", HttpMethod.POST,
                new HttpEntity<>(body, jsonWithToken(wardenToken(warden))), String.class);
    }

    private ResponseEntity<String> reject(SeededUser warden, long applicationId, String reason) {
        String body = json.createObjectNode().put("reason", reason).toString();
        return rest.exchange(WARDEN_APPLICATIONS + "/" + applicationId + "/reject", HttpMethod.POST,
                new HttpEntity<>(body, jsonWithToken(wardenToken(warden))), String.class);
    }

    private ResponseEntity<String> fetchApplication(SeededUser warden, long applicationId) {
        return rest.exchange(WARDEN_APPLICATIONS + "/" + applicationId, HttpMethod.GET,
                new HttpEntity<>(jsonWithToken(wardenToken(warden))), String.class);
    }

    /**
     * Materialises a JSON array as a list before asserting on it. AssertJ's overload
     * for {@code JsonNode} is the single-object one, so {@code assertThat(node)} would
     * offer no collection assertions at all -- and {@code JsonNode} being iterable is
     * exactly the kind of coincidence that makes that failure confusing.
     */
    private static List<JsonNode> elements(JsonNode array) {
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        return elements;
    }

    /** Reads the scope back off the seeded row so a warden's token always matches their account. */
    private String wardenToken(SeededUser warden) {
        String scope = jdbc.queryForObject(
                "SELECT hostel_scope FROM users WHERE id = ?", String.class, warden.userId());
        return accessTokenFor(warden, HostelScope.valueOf(scope));
    }

    private long applicationIdOf(ResponseEntity<String> response) {
        assertThat(response.getStatusCode())
                .as("apply() failed, so there is no application to decide: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response).path("id").asLong();
    }

    private String applicationStatusOf(long applicationId) {
        return jdbc.queryForObject(
                "SELECT status FROM applications WHERE id = ?", String.class, applicationId);
    }

    private boolean decidedAtIsNull(long applicationId) {
        Integer nulls = jdbc.queryForObject(
                "SELECT count(*) FROM applications WHERE id = ? AND decided_at IS NULL "
                        + "AND decided_by IS NULL",
                Integer.class, applicationId);
        return nulls != null && nulls == 1;
    }

    private Long decidedByOf(long applicationId) {
        return jdbc.queryForObject(
                "SELECT decided_by FROM applications WHERE id = ?", Long.class, applicationId);
    }

    /**
     * The student's allocation status as the enum, not the string the fixture hands back.
     *
     * <p>{@link #allocationStatusOf} returns the raw column, which is right for a base helper
     * that cannot know what each test wants to compare it to. Comparing that string to an
     * {@link AllocationStatus} can never pass, and the failure reads as a data problem rather
     * than the type error it is -- AssertJ prints {@code expected: PENDING but was: "PENDING"},
     * two values that differ only in quoting. Parsing here keeps every assertion above stated
     * in terms of the domain state it is actually about, and a status this application never
     * writes fails loudly at the {@code valueOf} instead of silently comparing unequal.
     */
    private AllocationStatus allocationStateOf(long studentId) {
        return AllocationStatus.valueOf(allocationStatusOf(studentId));
    }

    /**
     * A user's display name, read back off the row.
     *
     * <p>{@code ApplicationResponse.decidedBy} carries the deciding warden's display name
     * rather than their username or id, so a student reading a decision sees a person. Read
     * from the database rather than rebuilt as {@code "IT Warden " + username}, for the same
     * reason {@link #wardenToken} reads the scope back: a test that restates what the fixture
     * constructs stops testing the response the day the fixture changes shape.
     */
    private String displayNameOf(SeededUser user) {
        return jdbc.queryForObject(
                "SELECT full_name FROM users WHERE id = ?", String.class, user.userId());
    }

    private int pendingApplicationCount(long studentId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM applications WHERE student_id = ? AND status = 'PENDING'",
                Integer.class, studentId);
        return count == null ? 0 : count;
    }

    private int applicationCount(long studentId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM applications WHERE student_id = ?", Integer.class, studentId);
        return count == null ? 0 : count;
    }

    private JsonNode body(ResponseEntity<String> response) {
        try {
            return json.readTree(response.getBody());
        } catch (Exception ex) {
            throw new AssertionError("Response body was not JSON: " + response.getBody(), ex);
        }
    }
}
