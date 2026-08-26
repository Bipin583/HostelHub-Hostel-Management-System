package com.hostelops.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hostelops.config.AttendanceProperties;
import com.hostelops.domain.AttendanceStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.HostelType;
import com.hostelops.domain.PaymentStatus;
import com.hostelops.domain.Role;
import com.hostelops.security.AppUserPrincipal;
import com.hostelops.security.JwtService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests that need a real PostgreSQL.
 *
 * <h2>Why a real database</h2>
 *
 * <p>H2 in Postgres-compatibility mode would run faster and prove less. The
 * things these tests exist to verify are all Postgres-specific: {@code SELECT ...
 * FOR UPDATE} blocking semantics under READ COMMITTED, partial unique indexes,
 * {@code plpgsql} triggers, {@code JSONB}, and the exact constraint names that
 * {@code GlobalExceptionHandler} maps to error codes. A compatibility layer that
 * got any of those subtly wrong would produce a green suite and a broken
 * production system.
 *
 * <h2>Container lifecycle</h2>
 *
 * <p>Started once in a static initialiser and never stopped -- the "singleton
 * container" pattern. Deliberately <em>not</em> {@code @Testcontainers} with
 * {@code @Container}, because that extension stops the container after each test
 * class, and Failsafe runs every IT in one JVM. One Postgres start for the whole
 * {@code verify} phase instead of one per class is worth roughly a second per
 * class, and Ryuk still reaps the container when the JVM exits.
 *
 * <p>Flyway then migrates it on first context startup, so these tests run against
 * the same DDL that production does, constraint names included.
 *
 * <h2>Data isolation</h2>
 *
 * <p>The database is shared and nothing is truncated between tests. Every fixture
 * below therefore gets a globally unique suffix, and assertions are written
 * against the ids a test seeded rather than against table totals. That makes the
 * tests order-independent, which a truncate-between-tests scheme only appears to
 * do.
 */
@ActiveProfiles("integration")
public abstract class AbstractPostgresIT {

    /**
     * Pinned to a minor version. {@code postgres:latest} would mean a CI run can
     * fail because someone else released software.
     */
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.4-alpine"));

    static {
        POSTGRES.start();
    }

    /**
     * A syntactically well-formed but computationally unusable BCrypt hash.
     *
     * <p>Fixtures that authenticate by JWT never need a password, and giving them
     * a real one would put a working credential in the repository. This value
     * matches nothing: {@code BCryptPasswordEncoder.matches} returns false for
     * every input. Tests that genuinely exercise the password path (see
     * {@code AuthFlowIT}) encode a password chosen at runtime instead.
     */
    private static final String UNUSABLE_PASSWORD_HASH = "{bcrypt}$2a$10$" + "0".repeat(53);

    /** Makes usernames, roll numbers and room names unique across the whole run. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JwtService jwtService;

    /**
     * The application's own encoder, so a fixture password is hashed by exactly
     * the mechanism production uses. Hand-writing a BCrypt string here would test
     * the test's idea of BCrypt rather than the application's.
     */
    @Autowired
    protected PasswordEncoder passwordEncoder;

    /**
     * The application's own calendar, so a fixture's idea of a working day is the
     * detector's idea of one.
     *
     * <p>{@code AbsenceAlertService} counts a streak in working days, skipping
     * weekends and configured holidays. A fixture that marked ten consecutive
     * <em>calendar</em> days absent would seed eight working days across a weekend,
     * the scan would find a streak of eight, and the test would report a threshold
     * bug that does not exist. Reading the same bean removes the possibility of the
     * two disagreeing.
     */
    @Autowired
    protected AttendanceProperties attendanceProperties;

    /** For reading the error envelope out of a response body. */
    @Autowired
    protected ObjectMapper json;

    // ---- fixture records ----

    protected record SeededUser(long userId, String username) {
    }

    protected record SeededStudent(
            long userId, long studentId, String username, String rollNumber, Gender gender, int year) {
    }

    protected record SeededRoom(long roomId, String roomName, HostelType hostelType, int capacity, int eligibleYear) {
    }

    protected record SeededFee(
            long feeId, long studentId, String title, long amountPaise, LocalDate dueDate) {
    }

    /**
     * One row of {@code absence_alerts}, read straight from the table.
     *
     * <p>Deliberately not the API's response DTO. These tests assert that a second
     * scan left an alert alone, and "alone" includes the fields the DTO does not
     * expose -- {@code notified_at} in particular, since a re-notified student is
     * the failure the idempotency test exists to catch.
     */
    protected record AlertRow(
            long id,
            LocalDate streakStartDate,
            int consecutiveDays,
            LocalDate triggeredOn,
            boolean notified,
            boolean acknowledged) {
    }

    // ---- seeding ----

    protected long nextSequence() {
        return SEQUENCE.incrementAndGet();
    }

    protected SeededUser seedWarden(HostelScope scope) {
        String username = "it_warden_" + nextSequence();
        long id = insertUser(username, "IT Warden " + username, Role.WARDEN, scope.name());
        return new SeededUser(id, username);
    }

    protected SeededUser seedAdmin() {
        String username = "it_admin_" + nextSequence();
        long id = insertUser(username, "IT Admin " + username, Role.ADMIN, null);
        return new SeededUser(id, username);
    }

    /**
     * A warden who can actually sign in.
     *
     * <p>The password is supplied by the caller and hashed at runtime, so no
     * working credential is committed. Only tests that exercise the password
     * exchange itself need this; everything else mints a token directly.
     */
    protected SeededUser seedWardenWithPassword(HostelScope scope, String rawPassword) {
        String username = "it_login_" + nextSequence();
        long id = jdbc.queryForObject("""
                INSERT INTO users (username, password_hash, email, full_name, role, hostel_scope, enabled)
                VALUES (?, ?, ?, ?, 'WARDEN', ?, TRUE)
                RETURNING id
                """, Long.class,
                username, passwordEncoder.encode(rawPassword), username + "@example.edu",
                "IT Login " + username, scope.name());
        return new SeededUser(id, username);
    }

    protected void disableUser(long userId) {
        jdbc.update("UPDATE users SET enabled = FALSE WHERE id = ?", userId);
    }

    protected SeededStudent seedStudent(Gender gender, int yearOfStudy) {
        long sequence = nextSequence();
        String username = "it_student_" + sequence;
        // Fixed width on purpose. Assertions like "this listing does not mention
        // IT12" would otherwise be satisfied or broken by IT120 appearing in it,
        // and a fixed-width id can never be a prefix of another.
        String rollNumber = "IT%06d".formatted(sequence);
        long userId = insertUser(username, "IT Student " + sequence, Role.STUDENT, null);
        long studentId = jdbc.queryForObject("""
                INSERT INTO students (user_id, roll_number, gender, year_of_study, branch, allocation_status)
                VALUES (?, ?, ?, ?, 'Computer Science', 'NOT_APPLIED')
                RETURNING id
                """, Long.class, userId, rollNumber, gender.name(), yearOfStudy);
        return new SeededStudent(userId, studentId, username, rollNumber, gender, yearOfStudy);
    }

    /**
     * A room nothing else will touch.
     *
     * <p>Its block is {@code IT-<n>}, so it never collides with the reference
     * rooms {@code V2__room_reference_data.sql} seeds, and no other test's
     * allocations can land in it. That is what lets a test assert an exact
     * occupancy count.
     */
    protected SeededRoom seedRoom(HostelType hostelType, int eligibleYear, int capacity) {
        long sequence = nextSequence();
        String roomName = "IT" + sequence;
        Gender eligibleGender = hostelType == HostelType.LH ? Gender.F : Gender.M;
        long roomId = jdbc.queryForObject("""
                INSERT INTO rooms (room_name, hostel_type, block, floor, capacity, eligible_year, eligible_gender)
                VALUES (?, ?, ?, 1, ?, ?, ?)
                RETURNING id
                """, Long.class,
                roomName, hostelType.name(), "IT-" + sequence, capacity, eligibleYear, eligibleGender.name());
        return new SeededRoom(roomId, roomName, hostelType, capacity, eligibleYear);
    }

    private long insertUser(String username, String fullName, Role role, String hostelScope) {
        return jdbc.queryForObject("""
                INSERT INTO users (username, password_hash, email, full_name, role, hostel_scope, enabled)
                VALUES (?, ?, ?, ?, ?, ?, TRUE)
                RETURNING id
                """, Long.class,
                username, UNUSABLE_PASSWORD_HASH, username + "@example.edu", fullName, role.name(), hostelScope);
    }

    // ---- seeding fees ----

    /** An invoice with nothing paid against it. */
    protected SeededFee seedFee(long studentId, LocalDate dueDate, long amountPaise) {
        return seedFee(studentId, dueDate, amountPaise, 0L);
    }

    /**
     * An invoice, optionally part-paid.
     *
     * <p>The title carries the sequence because {@code uq_hostel_fees_term} is unique
     * over {@code (student_id, academic_year, semester, title)}. A fixture with a fixed
     * title would collide the second time one test seeded two invoices for the same
     * student, and the failure would look like a constraint bug in the code under test.
     *
     * <p>{@code status} is derived here rather than accepted as an argument, the same way
     * {@code HostelFee.recalculateStatus} derives it. A fixture that could set them
     * independently could create the one state the application will never produce -- a row
     * whose status disagrees with its own amounts -- and a test passing against that proves
     * nothing about production.
     */
    protected SeededFee seedFee(long studentId, LocalDate dueDate, long amountPaise, long paidPaise) {
        if (amountPaise <= 0 || paidPaise < 0 || paidPaise > amountPaise) {
            throw new IllegalArgumentException(
                    "%d paise paid against %d billed is not a state the schema permits"
                            .formatted(paidPaise, amountPaise));
        }
        String title = "IT Fee " + nextSequence();
        String status = paidPaise == 0 ? "UNPAID" : paidPaise == amountPaise ? "PAID" : "PARTIALLY_PAID";
        long feeId = jdbc.queryForObject("""
                INSERT INTO hostel_fees (student_id, title, academic_year, semester,
                                         amount_paise, amount_paid_paise, due_date, status)
                VALUES (?, ?, '2025-26', 'ODD', ?, ?, ?, ?)
                RETURNING id
                """, Long.class, studentId, title, amountPaise, paidPaise, dueDate, status);
        return new SeededFee(feeId, studentId, title, amountPaise, dueDate);
    }

    /** Settles an invoice in full without a payment, as a cash entry would. */
    protected void markFeePaid(long feeId) {
        jdbc.update("""
                UPDATE hostel_fees SET amount_paid_paise = amount_paise, status = 'PAID' WHERE id = ?
                """, feeId);
    }

    /** Writes an invoice off. Its outstanding balance becomes zero regardless of amounts. */
    protected void cancelFee(long feeId) {
        jdbc.update("UPDATE hostel_fees SET status = 'CANCELLED' WHERE id = ?", feeId);
    }

    /**
     * A reminder row written directly, as a previous night's run would have left it.
     *
     * <p>Lets a test start from a known prior state instead of from the job's own earlier
     * output, so what the job does next is measured against a fixture rather than against
     * an assumption about the code being tested.
     */
    protected void seedFeeReminder(long feeId, LocalDate reminderDate) {
        jdbc.update("""
                INSERT INTO fee_reminders (fee_id, reminder_date, channel) VALUES (?, ?, 'EMAIL')
                """, feeId, reminderDate);
    }

    // ---- seeding attendance ----

    /**
     * One attendance mark.
     *
     * <p>No upsert, deliberately. A test that seeds the same student and day twice is
     * asserting something about {@code uq_attendance_student_date}, and a fixture that
     * silently absorbed the collision would hide it.
     */
    protected void seedAttendanceMark(long studentId, LocalDate date, AttendanceStatus status) {
        jdbc.update("""
                INSERT INTO attendance (student_id, attendance_date, status) VALUES (?, ?, ?)
                """, studentId, date, status.name());
    }

    /**
     * Marks the student absent for {@code workingDays} working days ending at {@code lastDay}.
     *
     * <p>Working days, counted through {@link #attendanceProperties} -- see that field for
     * why calendar days would silently seed a shorter streak than the test asked for.
     *
     * <p>{@code lastDay} must itself be a working day. A run ending on a Sunday would be a
     * fixture whose last mark the scan never reads, and the resulting off-by-one is far
     * cheaper to reject here than to diagnose from a failed assertion about a count.
     *
     * @return the days marked, oldest first, so {@code get(0)} is the expected streak start
     */
    protected List<LocalDate> seedAbsentRun(long studentId, LocalDate lastDay, int workingDays) {
        if (!attendanceProperties.isWorkingDay(lastDay)) {
            throw new IllegalArgumentException(
                    lastDay + " is not a working day, so it cannot end a run of absence");
        }
        List<LocalDate> marked = new ArrayList<>(workingDays);
        LocalDate day = lastDay;
        while (marked.size() < workingDays) {
            if (attendanceProperties.isWorkingDay(day)) {
                seedAttendanceMark(studentId, day, AttendanceStatus.ABSENT);
                marked.add(day);
            }
            day = day.minusDays(1);
        }
        Collections.reverse(marked);
        return marked;
    }

    /**
     * Marks the working day before {@code day} present, which is what bounds a run.
     *
     * <p>Without it the detector's backward walk runs to the edge of its read-back window
     * and the streak has no defined first day. That is a legitimate state -- a student with
     * no earlier history -- but it is a different one, and a test meaning to assert an exact
     * streak length needs the boundary to be explicit.
     *
     * @return the day marked present
     */
    protected LocalDate seedPresentDayBefore(long studentId, LocalDate day) {
        LocalDate previous = day.minusDays(1);
        while (!attendanceProperties.isWorkingDay(previous)) {
            previous = previous.minusDays(1);
        }
        seedAttendanceMark(studentId, previous, AttendanceStatus.PRESENT);
        return previous;
    }

    /** Acknowledges an alert as a member of staff would, without going through the API. */
    protected void acknowledgeAlert(long alertId, long byUserId) {
        jdbc.update("""
                UPDATE absence_alerts SET acknowledged_by = ?, acknowledged_at = now() WHERE id = ?
                """, byUserId, alertId);
    }

    // ---- reading back ----

    protected long activeAllocationCount(long roomId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM allocations WHERE room_id = ? AND active", Long.class, roomId);
        return count == null ? 0L : count;
    }

    protected long activeAllocationCountForStudent(long studentId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM allocations WHERE student_id = ? AND active", Long.class, studentId);
        return count == null ? 0L : count;
    }

    protected String allocationStatusOf(long studentId) {
        return jdbc.queryForObject(
                "SELECT allocation_status FROM students WHERE id = ?", String.class, studentId);
    }

    /** Every reminder ever recorded against one invoice, on any date. */
    protected long reminderCountFor(long feeId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM fee_reminders WHERE fee_id = ?", Long.class, feeId);
        return count == null ? 0L : count;
    }

    /**
     * Reminders recorded against one invoice on one date.
     *
     * <p>The assertion the reminder tests are actually built on. It is scoped to a seeded
     * id, so it stays exact no matter what else the shared database holds -- which a count
     * of {@code fee_reminders} could never be.
     */
    protected long reminderCountFor(long feeId, LocalDate reminderDate) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM fee_reminders WHERE fee_id = ? AND reminder_date = ?
                """, Long.class, feeId, reminderDate);
        return count == null ? 0L : count;
    }

    /**
     * Every invoice the reminder job would examine for a run at {@code runDate}.
     *
     * <p>Repeats the predicate of {@code HostelFeeRepository.findDueForReminder}, which is
     * global and unscoped -- a job has no hostel of its own. So this is a whole-table count
     * and cannot be asserted against a literal. It exists only to cross-check the
     * {@code invoicesExamined} the job reports against the same question asked in SQL: if
     * those two disagree, the job is not looking at what it claims to be looking at.
     */
    protected long feesDueForReminderAt(LocalDate runDate, int leadDays) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM hostel_fees
                WHERE status IN ('UNPAID', 'PARTIALLY_PAID') AND due_date <= ?
                """, Long.class, runDate.plusDays(leadDays));
        return count == null ? 0L : count;
    }

    protected String feeStatusOf(long feeId) {
        return jdbc.queryForObject("SELECT status FROM hostel_fees WHERE id = ?", String.class, feeId);
    }

    protected long amountPaidPaiseOf(long feeId) {
        Long paid = jdbc.queryForObject(
                "SELECT amount_paid_paise FROM hostel_fees WHERE id = ?", Long.class, feeId);
        return paid == null ? 0L : paid;
    }

    protected String paymentStatusOf(long paymentId) {
        return jdbc.queryForObject("SELECT status FROM fee_payments WHERE id = ?", String.class, paymentId);
    }

    /**
     * The gateway reference recorded against an attempt, or null while it is pending.
     *
     * <p>Worth asserting on a rejected callback: a verification that failed must leave no
     * trace of the reference it was carrying, or a later reconciliation would read the row
     * as having been through the gateway.
     */
    protected String providerPaymentIdOf(long paymentId) {
        return jdbc.queryForObject(
                "SELECT provider_payment_id FROM fee_payments WHERE id = ?", String.class, paymentId);
    }

    protected long paymentCountForFee(long feeId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM fee_payments WHERE fee_id = ?", Long.class, feeId);
        return count == null ? 0L : count;
    }

    protected long paymentCountForFee(long feeId, PaymentStatus status) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM fee_payments WHERE fee_id = ? AND status = ?
                """, Long.class, feeId, status.name());
        return count == null ? 0L : count;
    }

    /** Every alert for one student, oldest streak first. */
    protected List<AlertRow> alertsFor(long studentId) {
        return jdbc.query("""
                SELECT id, streak_start_date, consecutive_days, triggered_on,
                       notified_at IS NOT NULL AS notified,
                       acknowledged_at IS NOT NULL AS acknowledged
                FROM absence_alerts
                WHERE student_id = ?
                ORDER BY streak_start_date, id
                """,
                (rs, rowNum) -> new AlertRow(
                        rs.getLong("id"),
                        rs.getObject("streak_start_date", LocalDate.class),
                        rs.getInt("consecutive_days"),
                        rs.getObject("triggered_on", LocalDate.class),
                        rs.getBoolean("notified"),
                        rs.getBoolean("acknowledged")),
                studentId);
    }

    /**
     * The single alert a student is expected to have.
     *
     * <p>Fails naming the count when there are none or several, because "a second scan
     * raised a duplicate" is the exact failure these tests are looking for and it should
     * read as that rather than as an index-out-of-bounds.
     */
    protected AlertRow theOnlyAlertFor(long studentId) {
        List<AlertRow> alerts = alertsFor(studentId);
        if (alerts.size() != 1) {
            throw new AssertionError("Expected exactly one absence alert for student %d, found %d: %s"
                    .formatted(studentId, alerts.size(), alerts));
        }
        return alerts.get(0);
    }

    // ---- authenticating ----

    /**
     * Mints a real access token for a seeded account.
     *
     * <p>Signed by the application's own {@link JwtService}, so the request goes
     * through the same filter, the same role gate and the same scope resolution a
     * browser's request would. Nothing here bypasses security: what is skipped is
     * only the password exchange, which would otherwise force every fixture to
     * carry a working credential.
     */
    protected String accessTokenFor(SeededUser warden, HostelScope scope) {
        return jwtService.issueAccessToken(new AppUserPrincipal(
                warden.userId(), warden.username(), null, Role.WARDEN, scope, null, true));
    }

    protected String accessTokenForAdmin(SeededUser admin) {
        return jwtService.issueAccessToken(new AppUserPrincipal(
                admin.userId(), admin.username(), null, Role.ADMIN, null, null, true));
    }

    protected String accessTokenForStudent(SeededStudent student) {
        return jwtService.issueAccessToken(new AppUserPrincipal(
                student.userId(), student.username(), null, Role.STUDENT, null,
                student.studentId(), true));
    }

    protected HttpHeaders jsonWithToken(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return headers;
    }

    // ---- racing ----

    /**
     * Runs {@code task} on {@code count} threads that are released together.
     *
     * <p>The two latches are the whole point. {@code ready} lets the caller wait until every
     * thread is parked and warmed up, and {@code go} releases them in one step, so the
     * requests genuinely overlap. Submitting to an executor without a gate staggers them by
     * however long thread creation takes, which is long enough for each request to finish
     * before the next starts -- and a test written that way passes against completely broken
     * locking, which is the usual reason this kind of test proves nothing.
     *
     * <p>Lives here rather than in one test class because every concurrency proof in the
     * suite needs the same gate, and a harness copied per class is a harness that drifts:
     * the copy someone loosens is the one whose test quietly stops overlapping.
     */
    protected <T> List<T> simultaneously(int count, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate never opened");
                    }
                    return task.apply(index);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("every worker reached the start gate").isTrue();
            go.countDown();

            List<T> results = new ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    protected static int statusCount(List<ResponseEntity<String>> responses, HttpStatus status) {
        return (int) responses.stream().filter(response -> response.getStatusCode() == status).count();
    }

    /**
     * A successful response body, read as the DTO the endpoint declares.
     *
     * <p>Every test here asks for {@code String.class} rather than the response type, so that a
     * request which unexpectedly fails is asserted against its status and its error envelope
     * rather than handed to Jackson, which would report the mismatch as a deserialization
     * problem somewhere inside the client. This turns the body into the record afterwards, once
     * the status has been checked.
     *
     * <p>Reading it through the application's own {@link #json} mapper is deliberate: the DTO
     * therefore has to be deserializable by exactly the configuration production serializes it
     * with, so a date format or naming strategy that only works one way fails here.
     *
     * <p>Fails naming the body it could not read, for the reason {@link #errorCodeOf} does.
     */
    protected <T> T bodyAs(ResponseEntity<String> response, Class<T> type) {
        try {
            return json.readValue(response.getBody(), type);
        } catch (Exception ex) {
            throw new AssertionError("Expected a %s body, got: %s"
                    .formatted(type.getSimpleName(), response.getBody()), ex);
        }
    }

    /**
     * The domain error code from a failed response.
     *
     * <p>Fails naming the body it could not parse, because the interesting failure is a
     * response that is not the standard envelope at all -- a framework 500 or a raw stack
     * trace -- and a {@code NullPointerException} deep in Jackson would hide that.
     */
    protected String errorCodeOf(ResponseEntity<String> response) {
        try {
            JsonNode root = json.readTree(response.getBody());
            return root.path("error").path("code").asText();
        } catch (Exception ex) {
            throw new AssertionError("Expected the standard error envelope, got: " + response.getBody(), ex);
        }
    }
}
