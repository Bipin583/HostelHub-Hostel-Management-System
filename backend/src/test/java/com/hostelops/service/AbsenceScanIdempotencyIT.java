package com.hostelops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hostelops.domain.AttendanceStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelScope;
import com.hostelops.dto.attendance.AbsenceScanResultResponse;
import com.hostelops.support.AbstractPostgresIT;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
 * The proof that the absence scan can run every night without raising an alert every night.
 *
 * <p>{@code AbsenceAlertService}, {@code AdminJobController} and {@code AbsenceScanResultResponse}
 * all cite this class by name. The detector's hard problem is not finding a ten-day absence --
 * that is a backwards walk over a map -- but doing it repeatedly against a moving window without
 * fabricating a new streak each time. Three things defend that, and each has a case here: an open
 * alert remembering where the run began, {@code uq_absence_alert_streak} refusing a second row
 * for the same start, and the lookup that extends an acknowledged alert instead of colliding
 * with it.
 *
 * <h2>Which numbers are safe to assert</h2>
 *
 * <p>A scan is campus-wide and this suite shares one database, so {@code studentsExamined} and
 * {@code extended} count students and alerts belonging to other tests -- and {@code extended} in
 * particular is incremented for every alert that survives a scan, whether or not its day count
 * actually changed. Neither can be pinned to a literal.
 *
 * <p>{@code raised == 0} on a repeat scan <em>is</em> globally safe, and it is the claim: if
 * running the scan twice raised anything for anybody, this fails. Everything else is asserted
 * per student, through {@link AbstractPostgresIT#theOnlyAlertFor}, which reads the row rather
 * than the counters.
 *
 * <h2>Why the anchor date is derived rather than written down</h2>
 *
 * <p>Every fixture here is measured in working days, so a run that ends on a Saturday is a run
 * whose last mark the detector never reads. {@link #anchor} walks back from a fixed date to the
 * nearest working day using the application's own calendar, so the fixtures stay correct without
 * this class hard-coding a day of the week -- and stay correct if a holiday is ever configured.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AbsenceScanIdempotencyIT extends AbstractPostgresIT {

    private static final String ABSENCE_SCANS = "/api/v1/admin/jobs/absence-scans";

    /**
     * The date the working-day anchor is derived from. Any fixed weekday would do; what
     * matters is that it is fixed, so a fixture is not written against today.
     */
    private static final LocalDate ANCHOR_SEED = LocalDate.of(2026, 5, 15);

    @Autowired
    private TestRestTemplate rest;

    // ------------------------------------------------------------------
    // The headline case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a run over the threshold raises one alert, and scanning again changes nothing")
    void aSecondScanOnUnchangedDataRaisesNothing() {
        String token = accessTokenForAdmin(seedAdmin());
        LocalDate lastDay = anchor();
        long studentId = seedStudent(Gender.F, 2).studentId();

        List<LocalDate> absent = seedAbsentRun(studentId, lastDay, threshold());
        seedPresentDayBefore(studentId, absent.get(0));

        AbsenceScanResultResponse first = scan(token, lastDay);

        // 1. The alert exists, and it says what the run actually was. The day count and the
        //    start date are the two things a warden acts on, and the start date is also what
        //    the constraint keys on -- so a wrong one is both a wrong alert and a second one
        //    tomorrow night.
        AlertRow raised = theOnlyAlertFor(studentId);
        assertThat(raised.streakStartDate()).isEqualTo(absent.get(0));
        assertThat(raised.consecutiveDays()).isEqualTo(threshold());
        assertThat(raised.triggeredOn()).isEqualTo(lastDay);
        assertThat(raised.acknowledged()).as("nobody has seen it yet").isFalse();
        assertThat(raised.notified()).as("and the student was told once").isTrue();
        Timestamp told = notifiedAt(raised.id());

        assertThat(first.scanDate()).isEqualTo(lastDay);
        // Not a literal: the scan is campus-wide, so these count other tests' students too.
        assertThat(first.studentsExamined()).isPositive();
        assertThat(first.raised()).isPositive();

        AbsenceScanResultResponse second = scan(token, lastDay);

        // 2. The claim, and the one counter that can be asserted globally. Nothing anywhere
        //    on campus was raised a second time.
        assertThat(second.raised()).as("a repeat scan raises nothing for anybody").isZero();
        // closed is deliberately not asserted: it counts other tests' students too, and this
        // student's alert being left open is covered exactly by the row comparison below.
        // extended counts every surviving alert in the database, this one included, so it is
        // positive rather than 1 -- the per-row assertion below is what makes it exact.
        assertThat(second.extended()).isPositive();

        // 3. Byte for byte the same alert: same row, same count, same trigger date, still
        //    unacknowledged. A detector that recomputed the streak start from the window
        //    would have inserted a second row here rather than left this one alone.
        assertThat(theOnlyAlertFor(studentId)).isEqualTo(raised);

        // 4. And nobody was emailed twice. This is the failure that a boolean "notified"
        //    flag would hide, so the timestamp itself is compared: notify() runs on the
        //    scan that raises an alert and never on the scans that extend it.
        assertThat(notifiedAt(raised.id())).isEqualTo(told);
    }

    @Test
    @DisplayName("a run one day short of the threshold raises nothing")
    void aRunBelowTheThresholdIsNotAnAlert() {
        String token = accessTokenForAdmin(seedAdmin());
        LocalDate lastDay = anchor();
        long studentId = seedStudent(Gender.M, 1).studentId();

        List<LocalDate> absent = seedAbsentRun(studentId, lastDay, threshold() - 1);
        seedPresentDayBefore(studentId, absent.get(0));

        scan(token, lastDay);

        // The boundary is worth its own case in both directions: without it the threshold
        // could be off by one and the case above would still pass.
        assertThat(alertsFor(studentId))
                .as("alerts for a student absent %d of the %d working days needed",
                        threshold() - 1, threshold())
                .isEmpty();
    }

    @Test
    @DisplayName("an absence that continues extends the same alert rather than raising another")
    void aContinuingAbsenceExtendsTheSameAlert() {
        String token = accessTokenForAdmin(seedAdmin());
        LocalDate lastDay = anchor();
        long studentId = seedStudent(Gender.F, 3).studentId();

        List<LocalDate> absent = seedAbsentRun(studentId, lastDay, threshold());
        seedPresentDayBefore(studentId, absent.get(0));
        AlertRow raised = scanAndReadAlert(token, lastDay, studentId);

        // Two more days away. The streak start has not moved, so the constraint would
        // reject a second alert -- which is why the service looks for the open one first.
        List<LocalDate> more = workingDaysAfter(lastDay, 2);
        for (LocalDate day : more) {
            seedAttendanceMark(studentId, day, AttendanceStatus.ABSENT);
        }

        AbsenceScanResultResponse later = scan(token, more.get(1));

        AlertRow extended = theOnlyAlertFor(studentId);
        assertThat(extended.id()).as("the same alert, not a new one").isEqualTo(raised.id());
        assertThat(extended.streakStartDate())
                .as("the start is remembered by the alert, never recomputed")
                .isEqualTo(raised.streakStartDate());
        assertThat(extended.consecutiveDays()).isEqualTo(threshold() + 2);
        assertThat(extended.triggeredOn())
                .as("triggeredOn records when the alert was raised, not when it was last touched")
                .isEqualTo(raised.triggeredOn());
        assertThat(later.raised()).isZero();
    }

    // ------------------------------------------------------------------
    // Closing, and re-opening
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a student who comes back has their alert closed by the system, not by a warden")
    void aReturnClosesTheAlertWithoutAnActor() {
        String token = accessTokenForAdmin(seedAdmin());
        LocalDate lastDay = anchor();
        long studentId = seedStudent(Gender.M, 4).studentId();

        List<LocalDate> absent = seedAbsentRun(studentId, lastDay, threshold());
        seedPresentDayBefore(studentId, absent.get(0));
        AlertRow raised = scanAndReadAlert(token, lastDay, studentId);

        LocalDate returned = workingDaysAfter(lastDay, 1).get(0);
        seedAttendanceMark(studentId, returned, AttendanceStatus.PRESENT);

        AbsenceScanResultResponse closing = scan(token, returned);

        // Nobody is left to acknowledge an alert about an absence that has ended, so it
        // would sit in the warden's queue forever. The scan closes it.
        assertThat(closing.closed()).isPositive();
        AlertRow closed = theOnlyAlertFor(studentId);
        assertThat(closed.id()).isEqualTo(raised.id());
        assertThat(closed.acknowledged()).isTrue();

        // And it stays distinguishable from a warden who actually looked at it: the frozen
        // schema's only expression for "closed by the system" is a null actor, and this is
        // the assertion that keeps that meaning honest.
        assertThat(acknowledgedBy(closed.id()))
                .as("acknowledged_by for a system close")
                .isNull();

        // A closed alert is not re-raised on the next scan either, which it would be if
        // closing were the only thing stopping the student's old run from being rediscovered.
        AbsenceScanResultResponse after = scan(token, returned);
        assertThat(after.raised()).isZero();
        assertThat(alertsFor(studentId)).hasSize(1);
    }

    @Test
    @DisplayName("an acknowledged alert is extended rather than duplicated when the absence goes on")
    void anAcknowledgedAlertIsExtendedNotDuplicated() {
        SeededUser admin = seedAdmin();
        String token = accessTokenForAdmin(admin);
        LocalDate lastDay = anchor();
        long studentId = seedStudent(Gender.F, 1).studentId();

        List<LocalDate> absent = seedAbsentRun(studentId, lastDay, threshold());
        seedPresentDayBefore(studentId, absent.get(0));
        AlertRow raised = scanAndReadAlert(token, lastDay, studentId);

        // A warden sees the alert and marks it handled -- and the student stays away. The
        // alert is no longer open, so the next scan's "is there an open alert?" lookup
        // misses it, and the streak start it would insert is the one already taken. This
        // is the path uq_absence_alert_streak would otherwise reject, and it is why the
        // service asks for an alert by streak before raising one.
        acknowledgeAlert(raised.id(), admin.userId());
        List<LocalDate> more = workingDaysAfter(lastDay, 3);
        for (LocalDate day : more) {
            seedAttendanceMark(studentId, day, AttendanceStatus.ABSENT);
        }

        AbsenceScanResultResponse later = scan(token, more.get(2));

        assertThat(later.raised()).isZero();
        AlertRow extended = theOnlyAlertFor(studentId);
        assertThat(extended.id()).isEqualTo(raised.id());
        assertThat(extended.consecutiveDays()).isEqualTo(threshold() + 3);
        assertThat(extended.acknowledged())
                .as("extending does not reopen what a warden has already seen")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Gaps in the register
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a day nobody took the roll neither breaks a run nor counts towards raising one")
    void anUnmarkedWorkingDayIsNeitherAnAbsenceNorAReturn() {
        String token = accessTokenForAdmin(seedAdmin());
        LocalDate lastDay = anchor();
        long studentId = seedStudent(Gender.M, 2).studentId();

        int window = threshold() + 2;
        int gapIndex = window / 2;
        List<LocalDate> days = workingDaysEndingAt(lastDay, window);
        for (int i = 0; i < window; i++) {
            if (i != gapIndex) {
                seedAttendanceMark(studentId, days.get(i), AttendanceStatus.ABSENT);
            }
        }
        seedPresentDayBefore(studentId, days.get(0));

        scan(token, lastDay);

        // 1. The gap did not break the run. Had it, the only run left would be the days
        //    after it -- well under the threshold -- and there would be no alert at all.
        //    One missed roll call must not reset every alert in the hostel.
        AlertRow raised = theOnlyAlertFor(studentId);
        assertThat(raised.streakStartDate())
                .as("the run reaches back past the unmarked day")
                .isEqualTo(days.get(0));

        // 2. And the gap did not count towards raising it: an alert has to be evidenced,
        //    so a day nobody recorded contributes nothing to establishing one.
        assertThat(raised.consecutiveDays())
                .as("%d working days in the window, %d of them marked absent", window, window - 1)
                .isEqualTo(window - 1);

        AbsenceScanResultResponse second = scan(token, lastDay);

        // 3. The documented asymmetry, pinned here because it is surprising enough to be
        //    mistaken for a bug. Measuring a run that is already established is a different
        //    question from establishing one: the alert attests to an absence spanning this
        //    day, so the recount spans it too and the count reaches the full window. The
        //    scan is still idempotent -- it raised nothing, and the next scan will recount
        //    to the same number and write nothing.
        assertThat(second.raised()).isZero();
        AlertRow recounted = theOnlyAlertFor(studentId);
        assertThat(recounted.id()).isEqualTo(raised.id());
        assertThat(recounted.consecutiveDays()).isEqualTo(window);
        assertThat(theOnlyAlertFor(studentId))
                .as("a third scan settles: the count grows once and then stops")
                .isEqualTo(recounted);
    }

    // ------------------------------------------------------------------
    // Defence in depth, and the route itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the database refuses a second alert for the same streak")
    void theUniqueConstraintCatchesWritesThatBypassTheScan() {
        String token = accessTokenForAdmin(seedAdmin());
        LocalDate lastDay = anchor();
        long studentId = seedStudent(Gender.F, 4).studentId();

        List<LocalDate> absent = seedAbsentRun(studentId, lastDay, threshold());
        seedPresentDayBefore(studentId, absent.get(0));
        AlertRow raised = scanAndReadAlert(token, lastDay, studentId);

        // Two scans overlapping is the case this covers: the loser's insert lands here.
        // The service is written to make it unreachable in the ordinary path, and the
        // constraint is what makes that a claim about the data rather than about the code.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO absence_alerts (student_id, streak_start_date, consecutive_days, triggered_on)
                VALUES (?, ?, ?, ?)
                """, studentId, raised.streakStartDate(), threshold(), lastDay))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_absence_alert_streak");

        assertThat(alertsFor(studentId)).hasSize(1);
    }

    @Test
    @DisplayName("a warden cannot trigger a campus-wide scan")
    void theScanRouteIsAdminOnly() {
        SeededUser warden = seedWarden(HostelScope.LH);
        LocalDate lastDay = anchor();
        long studentId = seedStudent(Gender.F, 2).studentId();
        seedAbsentRun(studentId, lastDay, threshold());

        ResponseEntity<String> response = postScan(accessTokenFor(warden, HostelScope.LH), lastDay);

        // A scan reads the whole register and raises alerts for students in every hostel;
        // it applies no AccessScope, deliberately, because a job has no hostel. The route
        // prefix is therefore the entire control.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("FORBIDDEN");
        assertThat(alertsFor(studentId)).as("and nothing ran").isEmpty();
    }

    // ------------------------------------------------------------------
    // harness
    // ------------------------------------------------------------------

    /** The configured threshold, read rather than restated: the fixtures are sized from it. */
    private int threshold() {
        return attendanceProperties.absenceAlertThreshold();
    }

    private AbsenceScanResultResponse scan(String token, LocalDate date) {
        ResponseEntity<String> response = postScan(token, date);
        assertThat(response.getStatusCode())
                .as("the scan itself must succeed: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return bodyAs(response, AbsenceScanResultResponse.class);
    }

    private ResponseEntity<String> postScan(String token, LocalDate date) {
        return rest.exchange(ABSENCE_SCANS + "?date=" + date, HttpMethod.POST,
                new HttpEntity<>(jsonWithToken(token)), String.class);
    }

    /** Scans, then asserts the student came out of it with exactly one alert, and returns it. */
    private AlertRow scanAndReadAlert(String token, LocalDate date, long studentId) {
        scan(token, date);
        return theOnlyAlertFor(studentId);
    }

    /**
     * A fixed working day to hang the fixtures on.
     *
     * <p>Derived through the application's own calendar rather than written down, so this
     * class never has to be right about which day of the week a date falls on -- and so a
     * configured holiday moves the fixtures instead of breaking them.
     */
    private LocalDate anchor() {
        LocalDate day = ANCHOR_SEED;
        while (!attendanceProperties.isWorkingDay(day)) {
            day = day.minusDays(1);
        }
        return day;
    }

    /** The last {@code count} working days up to and including {@code lastDay}, oldest first. */
    private List<LocalDate> workingDaysEndingAt(LocalDate lastDay, int count) {
        List<LocalDate> days = new ArrayList<>(count);
        LocalDate day = lastDay;
        while (days.size() < count) {
            if (attendanceProperties.isWorkingDay(day)) {
                days.add(day);
            }
            day = day.minusDays(1);
        }
        Collections.reverse(days);
        return days;
    }

    /** The next {@code count} working days strictly after {@code day}, oldest first. */
    private List<LocalDate> workingDaysAfter(LocalDate day, int count) {
        List<LocalDate> days = new ArrayList<>(count);
        LocalDate next = day.plusDays(1);
        while (days.size() < count) {
            if (attendanceProperties.isWorkingDay(next)) {
                days.add(next);
            }
            next = next.plusDays(1);
        }
        return days;
    }

    /**
     * When the student behind this alert was emailed, or null if nobody was.
     *
     * <p>Read as a timestamp rather than through {@link AlertRow#notified}, because the
     * failure being watched for is a <em>second</em> notification, and a boolean cannot tell
     * one email from two.
     */
    private Timestamp notifiedAt(long alertId) {
        return jdbc.queryForObject(
                "SELECT notified_at FROM absence_alerts WHERE id = ?", Timestamp.class, alertId);
    }

    /** Who acknowledged this alert, or null when the scan closed it. */
    private Long acknowledgedBy(long alertId) {
        return jdbc.queryForObject(
                "SELECT acknowledged_by FROM absence_alerts WHERE id = ?", Long.class, alertId);
    }
}
