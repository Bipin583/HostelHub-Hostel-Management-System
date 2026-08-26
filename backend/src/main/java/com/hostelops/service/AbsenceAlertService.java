package com.hostelops.service;

import com.hostelops.audit.AuditEntity;
import com.hostelops.audit.Audited;
import com.hostelops.config.AttendanceProperties;
import com.hostelops.domain.AbsenceAlert;
import com.hostelops.domain.AttendanceStatus;
import com.hostelops.domain.AuditAction;
import com.hostelops.domain.Student;
import com.hostelops.domain.UserAccount;
import com.hostelops.dto.attendance.AbsenceAlertResponse;
import com.hostelops.dto.attendance.AbsenceScanResultResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.AbsenceAlertMapper;
import com.hostelops.notify.Notification;
import com.hostelops.notify.NotificationException;
import com.hostelops.notify.Notifier;
import com.hostelops.repository.AbsenceAlertRepository;
import com.hostelops.repository.AttendanceMarkRow;
import com.hostelops.repository.AttendanceRepository;
import com.hostelops.repository.StudentRepository;
import com.hostelops.repository.UserAccountRepository;
import com.hostelops.security.AccessScope;
import com.hostelops.security.CurrentUserProvider;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Detects runs of absence, and owns the alerts they raise.
 *
 * <h2>What the detector is replacing</h2>
 *
 * <p>The predecessor answered "who has been absent ten days?" with a live query, which is
 * the version of this feature that cannot be acted on -- see {@link AbsenceAlert} for why a
 * persisted alert is a different thing from a dashboard number. What follows is about the
 * three problems that persisting it creates.
 *
 * <h2>1. The window would otherwise fabricate a new streak every night</h2>
 *
 * <p>The scan reads back {@link #READ_BACK_DAYS} days of marks, not the whole table. Once a
 * streak is longer than that, its true first day is outside the window, so a scan that
 * derived the start date purely from marks would compute a later start each night -- a
 * different {@code (student_id, streak_start_date)} every time, a new row past
 * {@code uq_absence_alert_streak} every time, and one alert per night for one absence.
 * That is the exact failure the constraint exists to prevent, arrived at by giving it a
 * different key each run.
 *
 * <p>So an open alert is treated as the authoritative memory of where the run began. If a
 * student has one, its {@code streakStartDate} is not recomputed; the day count is
 * recounted <em>from</em> that date and can only grow. The alert, not the window, is what
 * remembers.
 *
 * <h2>2. Unmarked days are not absences, and are not returns either</h2>
 *
 * <p>{@link com.hostelops.domain.Attendance} states the rule this class implements: a gap
 * in the register is a day nobody took the roll, not a day everybody was away. So when the
 * scan walks backwards looking for the current run, an unmarked working day neither counts
 * as an absence nor breaks the run -- it is skipped. Breaking on it would mean one missed
 * roll call resets every alert in the hostel; counting it would mean a fortnight's
 * administrative silence raises an alert against every student on the register.
 *
 * <p>The one place unmarked days do get counted is the recount described above, and the
 * asymmetry is deliberate. Establishing a run needs evidence, so a gap cannot contribute to
 * one. Measuring a run that has already been established and has not been broken -- if it
 * had been, the alert would have been closed rather than recounted -- is a different
 * question, and there a register nobody took does not shorten an absence that the days
 * either side of it attest to.
 *
 * <h2>3. Coming back has to close the alert, and nobody is there to do it</h2>
 *
 * <p>A student who returns leaves their alert sitting in the warden's queue forever, because
 * acknowledging is a human act and there is nothing left for a human to act on. The scan
 * closes it, and the only expression the frozen schema offers is
 * {@code acknowledge(null, now)} -- {@code acknowledged_by} is nullable, so a null actor
 * reads as "closed by the system" and stays distinguishable in the data from a warden who
 * saw it. {@code AbsenceScanResultResponse.closed} is that count.
 *
 * <h2>Idempotency, and what happens when two scans overlap</h2>
 *
 * <p>Re-running the scan on unchanged data extends the same alerts to the same day counts
 * and raises nothing, which is what {@code AbsenceScanIdempotencyIT} asserts. Two scans
 * running at once are stopped by {@code uq_absence_alert_streak} rather than by a lock:
 * the loser's insert fails, and because Postgres aborts a transaction on a constraint
 * violation, it loses the whole run rather than the one student. That is acceptable
 * precisely because the scan is idempotent -- the next run redoes the work and reaches the
 * same state. Per-student savepoints would salvage the rest of the batch and are not worth
 * the complexity for a nightly job whose recovery is "run it again".
 */
@Service
public class AbsenceAlertService {

    private static final Logger log = LoggerFactory.getLogger(AbsenceAlertService.class);

    /**
     * How far back one scan reads the register.
     *
     * <p>Calendar days, not working days, and generous: at a threshold of ten working days
     * this is several times the evidence needed to raise an alert, which keeps the
     * fabricated-start problem in section 1 confined to genuinely long absences where an
     * open alert is already remembering the start.
     *
     * <p>It is a window rather than a full-table scan because the query it feeds returns one
     * row per student per day. Over a year that is the whole register hydrated nightly to
     * answer a question about the last fortnight.
     */
    private static final int READ_BACK_DAYS = 90;

    private final AbsenceAlertRepository alerts;
    private final AttendanceRepository attendance;
    private final StudentRepository students;
    private final UserAccountRepository users;
    private final AttendanceProperties attendanceProperties;
    private final AbsenceAlertMapper alertMapper;
    private final CurrentUserProvider currentUser;
    private final Notifier notifier;

    public AbsenceAlertService(
            AbsenceAlertRepository alerts,
            AttendanceRepository attendance,
            StudentRepository students,
            UserAccountRepository users,
            AttendanceProperties attendanceProperties,
            AbsenceAlertMapper alertMapper,
            CurrentUserProvider currentUser,
            Notifier notifier) {
        this.alerts = alerts;
        this.attendance = attendance;
        this.students = students;
        this.users = users;
        this.attendanceProperties = attendanceProperties;
        this.alertMapper = alertMapper;
        this.currentUser = currentUser;
        this.notifier = notifier;
    }

    /**
     * Runs one scan.
     *
     * <p>Two queries of setup for the whole hostel: the register over the window, and every
     * open alert. Then one write per alert that changes. The per-student alternative --
     * "for each student, load their marks" -- is a query per student per night, and it is
     * the shape this method is written to avoid.
     *
     * <p>Takes the scan date rather than reading a clock, so the job passes today and a test
     * passes whatever date makes its fixture meaningful.
     *
     * <p>Audited as {@code UPDATE} with no entity id: a scan touches many alerts and no
     * single one, and the {@link AbsenceScanResultResponse} it returns is recorded in the
     * event's payload -- so the trail says what each run did, including the runs that
     * changed nothing.
     */
    @Audited(entity = AuditEntity.ABSENCE_ALERT, action = AuditAction.UPDATE)
    @Transactional
    public AbsenceScanResultResponse scan(LocalDate scanDate) {
        LocalDate horizon = scanDate.minusDays(READ_BACK_DAYS);
        int threshold = attendanceProperties.absenceAlertThreshold();
        Instant now = Instant.now();

        Map<Long, Map<LocalDate, AttendanceStatus>> register = registerSince(horizon);
        Map<Long, List<AbsenceAlert>> openByStudent = openAlertsByStudent();

        int raised = 0;
        int extended = 0;
        int closed = 0;

        for (Map.Entry<Long, Map<LocalDate, AttendanceStatus>> entry : register.entrySet()) {
            Long studentId = entry.getKey();
            Run run = currentRun(entry.getValue(), scanDate, horizon);

            List<AbsenceAlert> surviving = new ArrayList<>();
            for (AbsenceAlert alert : openByStudent.getOrDefault(studentId, List.of())) {
                if (hasReturned(run, alert)) {
                    alert.acknowledge(null, now);
                    alerts.save(alert);
                    closed++;
                } else {
                    surviving.add(alert);
                }
            }

            if (!surviving.isEmpty()) {
                // An open alert is already speaking for this student's absence, so the run
                // is measured from where that alert says it began -- section 1 -- and no
                // second alert is raised for the same continuing absence.
                for (AbsenceAlert alert : surviving) {
                    recount(alert, run);
                    extended++;
                }
                continue;
            }

            if (run == null || run.absentDays() < threshold) {
                continue;
            }

            // No open alert, but the streak may already have one that somebody acknowledged
            // and then the student stayed away. The lookup is what stops this from being an
            // insert that uq_absence_alert_streak rejects, and it costs one query only for
            // the students actually over the threshold.
            Optional<AbsenceAlert> acknowledged =
                    alerts.findByStudentIdAndStreakStartDate(studentId, run.start());
            if (acknowledged.isPresent()) {
                recount(acknowledged.get(), run);
                extended++;
                continue;
            }

            AbsenceAlert alert = raise(studentId, run, scanDate);
            notify(alert, now);
            raised++;
        }

        log.info("Absence scan {}: {} students examined, {} raised, {} extended, {} closed",
                scanDate, register.size(), raised, extended, closed);
        return new AbsenceScanResultResponse(scanDate, register.size(), raised, extended, closed);
    }

    /** Open alerts for the caller's students, oldest first. The warden's queue. */
    @Transactional(readOnly = true)
    public Page<AbsenceAlertResponse> openAlerts(Pageable pageable) {
        AccessScope scope = currentUser.scope();
        LocalDate today = today();
        return alerts.findOpenInScope(scope.visibleGenders(), pageable)
                .map(alert -> alertMapper.toResponse(alert, today));
    }

    /** Every alert the caller may see, newest first, acknowledged ones included. */
    @Transactional(readOnly = true)
    public Page<AbsenceAlertResponse> allAlerts(Pageable pageable) {
        AccessScope scope = currentUser.scope();
        LocalDate today = today();
        return alerts.findAllInScope(scope.visibleGenders(), pageable)
                .map(alert -> alertMapper.toResponse(alert, today));
    }

    /** How many alerts are waiting, for the dashboard tile. */
    @Transactional(readOnly = true)
    public long countOpen() {
        return alerts.countByAcknowledgedAtIsNullAndStudentGenderIn(currentUser.scope().visibleGenders());
    }

    /**
     * A student's own open alerts.
     *
     * <p>Exists so the student portal can show somebody why the office has been in touch.
     * {@code requireSelf} first, so a student asking for another student's alerts is
     * refused before any row is read.
     */
    @Transactional(readOnly = true)
    public List<AbsenceAlertResponse> openAlertsForStudent(Long studentId) {
        AccessScope scope = currentUser.scope();
        scope.requireSelf(studentId);
        LocalDate today = today();
        return alerts.findByStudentIdAndAcknowledgedAtIsNull(studentId).stream()
                .filter(alert -> scope.visibleGenders().contains(alert.getStudent().getGender()))
                .map(alert -> alertMapper.toResponse(alert, today))
                .toList();
    }

    /**
     * Records that a member of staff has seen an alert.
     *
     * <p>Acknowledging twice is a 409 rather than a no-op, because two wardens both
     * believing they handled a case is worth surfacing -- and because the alert already
     * names who acknowledged it, so silently overwriting that would erase the first one.
     *
     * <p>An alert the scan auto-closed is acknowledged too, and hits the same 409. That
     * reads correctly: the student came back, and there is nothing left to acknowledge.
     */
    @Audited(entity = AuditEntity.ABSENCE_ALERT, action = AuditAction.UPDATE, idParam = "alertId")
    @Transactional
    public AbsenceAlertResponse acknowledge(Long alertId) {
        AccessScope scope = currentUser.scope();
        AbsenceAlert alert = alerts.findByIdAndStudentGenderIn(alertId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("absence alert", alertId));

        if (alert.isAcknowledged()) {
            throw ApiException.conflict(ErrorCode.ALERT_ALREADY_ACKNOWLEDGED,
                    "This alert was already acknowledged");
        }

        alert.acknowledge(actor(), Instant.now());
        alerts.save(alert);
        return alertMapper.toResponse(alert, today());
    }

    /**
     * The window's marks, indexed by student and then date.
     *
     * <p>A map per student rather than the flat list the query returns, because the walk in
     * {@link #currentRun} asks "what happened on this specific day" once per working day and
     * wants that to be a hash lookup.
     */
    private Map<Long, Map<LocalDate, AttendanceStatus>> registerSince(LocalDate horizon) {
        Map<Long, Map<LocalDate, AttendanceStatus>> byStudent = new HashMap<>();
        for (AttendanceMarkRow row : attendance.findMarksSince(horizon)) {
            byStudent.computeIfAbsent(row.studentId(), id -> new HashMap<>())
                    .put(row.date(), row.status());
        }
        return byStudent;
    }

    private Map<Long, List<AbsenceAlert>> openAlertsByStudent() {
        Map<Long, List<AbsenceAlert>> byStudent = new HashMap<>();
        for (AbsenceAlert alert : alerts.findByAcknowledgedAtIsNull()) {
            byStudent.computeIfAbsent(alert.getStudent().getId(), id -> new ArrayList<>()).add(alert);
        }
        return byStudent;
    }

    /**
     * The run of absence the student is currently in, or null if they are not in one.
     *
     * <p>Walks working days backwards from the scan date and stops at the first day the
     * student was present -- that day, and not the absence of further rows, is what ends a
     * run. Non-working days are skipped by the calendar in {@link AttendanceProperties};
     * unmarked working days are skipped for the reason in section 2 of the class Javadoc.
     *
     * <p>{@code lastPresent} is carried out of the walk because it is the only thing that
     * can close an alert whose start date is older than the window: knowing the most recent
     * day a student was present is enough to say whether any given run has been broken,
     * without needing the marks from inside it.
     */
    private Run currentRun(Map<LocalDate, AttendanceStatus> marks, LocalDate scanDate, LocalDate horizon) {
        LocalDate start = null;
        LocalDate lastAbsent = null;
        LocalDate lastPresent = null;
        int absentDays = 0;

        for (LocalDate day = scanDate; !day.isBefore(horizon); day = day.minusDays(1)) {
            if (!attendanceProperties.isWorkingDay(day)) {
                continue;
            }
            AttendanceStatus status = marks.get(day);
            if (status == AttendanceStatus.PRESENT) {
                lastPresent = day;
                break;
            }
            if (status == AttendanceStatus.ABSENT) {
                absentDays++;
                start = day;
                if (lastAbsent == null) {
                    lastAbsent = day;
                }
            }
        }

        return start == null ? new Run(null, 0, null, lastPresent) : new Run(start, absentDays, lastAbsent, lastPresent);
    }

    /**
     * True when the student was present on or after the day this alert says their absence
     * began -- which is to say the run the alert describes is over.
     *
     * <p>Compared against the alert's own start date rather than against the freshly
     * computed run, so an alert whose start has fallen out of the window is still closable.
     * A student with no marks at all in the window is in neither state: nothing closes,
     * nothing extends, because nobody has said anything about them either way.
     */
    private boolean hasReturned(Run run, AbsenceAlert alert) {
        return run.lastPresent() != null && !run.lastPresent().isBefore(alert.getStreakStartDate());
    }

    /**
     * Brings an existing alert's day count up to date.
     *
     * <p>Counts working days from the date the alert already holds, so the number grows with
     * the absence and never shrinks when the window slides past the start. The guard is
     * what makes the scan idempotent in the observable sense: re-running it recomputes the
     * same count and writes nothing new.
     */
    private void recount(AbsenceAlert alert, Run run) {
        if (run.lastAbsent() == null) {
            return;
        }
        int days = workingDaysBetween(alert.getStreakStartDate(), run.lastAbsent());
        if (days > alert.getConsecutiveDays()) {
            alert.extendTo(days);
            alerts.save(alert);
        }
    }

    private AbsenceAlert raise(Long studentId, Run run, LocalDate scanDate) {
        AbsenceAlert alert = new AbsenceAlert();
        // A lazy reference, not a fetch: see StudentRepository#getReferenceById. The
        // notification path below does dereference it, which costs one query for the
        // student and one for their account -- paid only on the runs that raise an alert,
        // never on the far more common run that raises none.
        alert.setStudent(students.getReferenceById(studentId));
        alert.setStreakStartDate(run.start());
        alert.setConsecutiveDays(run.absentDays());
        alert.setTriggeredOn(scanDate);

        // Flushed rather than queued: uq_absence_alert_streak has to fire here, where the
        // failure is attributable to this student, not at commit after the run has reported
        // success.
        return alerts.saveAndFlush(alert);
    }

    /**
     * Tells the student their absence has been noticed.
     *
     * <p>The student rather than the warden, because the warden already has the queue that
     * {@link #openAlerts} serves, and because the account model has no
     * warden-for-this-hostel lookup -- inventing one to send an email would be the schema
     * bending to the notifier.
     *
     * <p>Sent once, on the run that raises the alert, and never on the runs that extend it.
     * {@code notified_at} is a single timestamp and could not record more than one anyway,
     * but the real reason is that a nightly reminder about the same unchanged absence is how
     * people learn to filter mail from this application.
     *
     * <p>A failure is logged and counted, not propagated: {@code notified_at} stays null,
     * which is exactly the record needed to see that the alert exists and nobody was told.
     * A student with no address on file lands here, since {@code users.email} is nullable.
     */
    private void notify(AbsenceAlert alert, Instant now) {
        Student student = alert.getStudent();
        UserAccount account = student.getUser();
        try {
            notifier.send(new Notification(
                    account.getEmail(),
                    "Absence alert: " + alert.getConsecutiveDays() + " consecutive days",
                    ("""
                            %s (%s),

                            The hostel register shows you absent for %d consecutive working \
                            days since %s. The hostel office has been notified and may \
                            contact you or your guardian.

                            If this is incorrect, raise it with your warden -- the register \
                            can be corrected.""")
                            .formatted(
                                    account.getFullName(),
                                    student.getRollNumber(),
                                    alert.getConsecutiveDays(),
                                    alert.getStreakStartDate())));
            alert.markNotified(now);
            alerts.save(alert);
        } catch (NotificationException e) {
            log.warn("Absence alert {} for student {} could not be delivered",
                    alert.getId(), student.getId(), e);
        }
    }

    /** Working days from {@code from} to {@code to}, both included. */
    private int workingDaysBetween(LocalDate from, LocalDate to) {
        int days = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (attendanceProperties.isWorkingDay(day)) {
                days++;
            }
        }
        return days;
    }

    /** Today in UTC, for the same reason {@code AttendanceService.today()} is. */
    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private UserAccount actor() {
        Long userId = currentUser.require().getUserId();
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL, "Acting account not found"));
    }

    /**
     * What the backward walk found: where the current run of absence starts, how many
     * working days of it are marked absent, the most recent of those, and the most recent
     * day the student was present.
     *
     * <p>{@code start} and {@code lastAbsent} are null when the student is not currently
     * absent, which is why callers test {@code absentDays} rather than trusting the dates.
     */
    private record Run(LocalDate start, int absentDays, LocalDate lastAbsent, LocalDate lastPresent) {
    }
}
