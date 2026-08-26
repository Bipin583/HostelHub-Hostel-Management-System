package com.hostelops.service;

import com.hostelops.audit.AuditEntity;
import com.hostelops.audit.Audited;
import com.hostelops.config.AttendanceProperties;
import com.hostelops.domain.Attendance;
import com.hostelops.domain.AttendanceStatus;
import com.hostelops.domain.AuditAction;
import com.hostelops.domain.Student;
import com.hostelops.domain.UserAccount;
import com.hostelops.dto.attendance.AttendanceBulkMarkRequest;
import com.hostelops.dto.attendance.AttendanceBulkMarkResponse;
import com.hostelops.dto.attendance.AttendanceMarkRequest;
import com.hostelops.dto.attendance.AttendanceResponse;
import com.hostelops.dto.attendance.AttendanceTrendResponse;
import com.hostelops.dto.attendance.StudentAttendanceSummaryResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.AttendanceMapper;
import com.hostelops.repository.AttendanceDayTotals;
import com.hostelops.repository.AttendanceRepository;
import com.hostelops.repository.StudentRepository;
import com.hostelops.repository.UserAccountRepository;
import com.hostelops.security.AccessScope;
import com.hostelops.security.CurrentUserProvider;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taking and reading the attendance register.
 *
 * <h2>Marking is an upsert, and that is a decision</h2>
 *
 * <p>A register is corrected: a student who signs in late was marked absent an hour ago,
 * and the evening's roll call gets re-submitted with three changes. So marking a student
 * for a date writes one row per student per date -- created the first time, overwritten
 * afterwards -- rather than appending a second row that a reader would have to
 * disambiguate by timestamp. {@code uq_attendance_student_date} is what makes that shape
 * enforceable, and {@code Attendance.remark} is where the overwrite lives.
 *
 * <p>The audit action on both marking methods is therefore {@code UPDATE}, never
 * {@code CREATE}, even when a row is genuinely new. The event being recorded is "the
 * register for this student on this date now says X", and whether a row had to be inserted
 * to say it is an implementation detail of the register, not something a warden did.
 * {@code CREATE} is reserved for records whose existence is itself the event -- an
 * application, an allocation, an invoice.
 *
 * <h2>What the register refuses</h2>
 *
 * <p>Future dates, and nothing else. Marking yesterday is normal and marking last week is
 * a late correction, so there is no backdating window; the one thing that cannot be a
 * correction is a date that has not happened.
 *
 * <p>Non-working days are permitted. A hostel that takes the roll on a Sunday is not
 * making a mistake, and the absence detector reads working days only -- so a weekend mark
 * is recorded, visible, and correctly ignored by the streak rule. Rejecting it here would
 * be this class inventing a policy that {@link AttendanceProperties} already owns.
 */
@Service
public class AttendanceService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceService.class);

    /**
     * Longest window a report may cover.
     *
     * <p>An academic year, plus a day. The trend and summary queries are indexed and cheap
     * per row, but an unbounded window lets one request ask for every mark ever taken, and
     * the answer to that is a chart nobody can read rather than a report somebody wanted.
     */
    private static final long MAX_REPORT_DAYS = 366;

    private final AttendanceRepository attendance;
    private final StudentRepository students;
    private final UserAccountRepository users;
    private final AttendanceMapper attendanceMapper;
    private final CurrentUserProvider currentUser;

    public AttendanceService(
            AttendanceRepository attendance,
            StudentRepository students,
            UserAccountRepository users,
            AttendanceMapper attendanceMapper,
            CurrentUserProvider currentUser) {
        this.attendance = attendance;
        this.students = students;
        this.users = users;
        this.attendanceMapper = attendanceMapper;
        this.currentUser = currentUser;
    }

    /**
     * Marks one student for one date.
     *
     * <p>Exists alongside {@link #markRegister} for the correction case: flipping one
     * student after the register was taken should not require resubmitting two hundred
     * rows, and a client that had to would be tempted to send a partial register instead.
     */
    @Audited(entity = AuditEntity.ATTENDANCE, action = AuditAction.UPDATE)
    @Transactional
    public AttendanceResponse markOne(AttendanceMarkRequest request) {
        AccessScope scope = currentUser.scope();
        requireMarkableDate(request.attendanceDate());

        Student student = students.findByIdAndGenderIn(request.studentId(), scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", request.studentId()));

        UserAccount actor = actor();
        Instant now = Instant.now();

        Attendance mark = attendance
                .findByStudentIdAndAttendanceDate(student.getId(), request.attendanceDate())
                .map(existing -> {
                    existing.remark(request.status(), actor, now);
                    return existing;
                })
                .orElseGet(() -> newMark(student, request.attendanceDate(), request.status(), actor, now));

        // Flushed here so that a concurrent first-mark for the same student and date hits
        // uq_attendance_student_date inside this method, where GlobalExceptionHandler
        // already maps it to DUPLICATE_RESOURCE, rather than at commit.
        attendance.saveAndFlush(mark);

        return attendanceMapper.toResponse(mark);
    }

    /**
     * Marks a whole register in one transaction.
     *
     * <p>Two queries of setup regardless of batch size: one scoped fetch of the named
     * students, one fetch of the marks that already exist for the date. Then one row
     * written per entry. The per-student alternative is two hundred round trips and, worse,
     * two hundred chances for the submission to stop halfway.
     *
     * <p>Ids the caller may not see, and ids that do not exist, are reported in
     * {@code skippedStudentIds} rather than failing the request -- the reasoning is on
     * {@link AttendanceBulkMarkResponse}. The two cases are deliberately not
     * distinguished in the response: telling a warden which of the ids they cannot see
     * exist anyway is the enumeration that {@code findByIdInAndGenderIn} exists to prevent.
     *
     * <p>A payload naming the same student twice keeps the last entry. Rejecting the
     * request would be defensible, but a duplicate row in a submitted register is a client
     * bug whose sensible reading is "the second one is the correction", and that is also
     * what re-marking the same student a second later would do.
     */
    @Audited(entity = AuditEntity.ATTENDANCE, action = AuditAction.UPDATE)
    @Transactional
    public AttendanceBulkMarkResponse markRegister(AttendanceBulkMarkRequest request) {
        AccessScope scope = currentUser.scope();
        LocalDate date = request.attendanceDate();
        requireMarkableDate(date);

        Map<Long, AttendanceStatus> wanted = new LinkedHashMap<>();
        for (AttendanceBulkMarkRequest.Entry entry : request.marks()) {
            wanted.put(entry.studentId(), entry.status());
        }

        Map<Long, Student> visible = students
                .findByIdInAndGenderIn(wanted.keySet(), scope.visibleGenders()).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));

        Map<Long, Attendance> existing = attendance
                .findByStudentIdInAndAttendanceDate(visible.keySet(), date).stream()
                .collect(Collectors.toMap(mark -> mark.getStudent().getId(), Function.identity()));

        UserAccount actor = actor();
        Instant now = Instant.now();

        List<Attendance> toSave = new ArrayList<>(wanted.size());
        List<Long> skipped = new ArrayList<>();
        int created = 0;
        int updated = 0;

        for (Map.Entry<Long, AttendanceStatus> entry : wanted.entrySet()) {
            Student student = visible.get(entry.getKey());
            if (student == null) {
                skipped.add(entry.getKey());
                continue;
            }
            Attendance mark = existing.get(student.getId());
            if (mark == null) {
                toSave.add(newMark(student, date, entry.getValue(), actor, now));
                created++;
            } else if (mark.getStatus() != entry.getValue()) {
                mark.remark(entry.getValue(), actor, now);
                toSave.add(mark);
                updated++;
            }
            // A row already saying what the submission says is left alone: re-submitting an
            // unchanged register should report 0 changed, not 200 updated, or the counts
            // stop being the reason the response splits them apart.
        }

        attendance.saveAll(toSave);
        attendance.flush();

        log.info("Register for {}: {} created, {} updated, {} skipped", date, created, updated, skipped.size());
        return new AttendanceBulkMarkResponse(date, created, updated, List.copyOf(skipped));
    }

    /** One date's register, for the students the caller may see. */
    @Transactional(readOnly = true)
    public Page<AttendanceResponse> register(LocalDate date, Pageable pageable) {
        AccessScope scope = currentUser.scope();
        return attendance.findRegisterFor(date, scope.visibleGenders(), pageable)
                .map(attendanceMapper::toResponse);
    }

    /**
     * One student's marks over a window.
     *
     * <p>{@code requireSelf} before the scoped lookup, so a student asking for somebody
     * else's history gets 403 rather than a 404 that would confirm the row exists.
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> historyForStudent(Long studentId, LocalDate from, LocalDate to) {
        AccessScope scope = currentUser.scope();
        scope.requireSelf(studentId);
        requireWindow(from, to);

        Student student = students.findByIdAndGenderIn(studentId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", studentId));

        return attendance
                .findByStudentIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(student.getId(), from, to)
                .stream()
                .map(attendanceMapper::toResponse)
                .toList();
    }

    /**
     * One student's attendance rate over a window.
     *
     * <p>Two counting queries rather than loading the rows and reducing them in Java. The
     * rate over a semester is a hundred-odd rows today and the same two queries when the
     * table holds a million; more to the point, counting in the database means the two
     * numbers come from {@code idx_attendance_student_date} without materialising anything.
     */
    @Transactional(readOnly = true)
    public StudentAttendanceSummaryResponse summaryForStudent(Long studentId, LocalDate from, LocalDate to) {
        AccessScope scope = currentUser.scope();
        scope.requireSelf(studentId);
        requireWindow(from, to);

        Student student = students.findByIdAndGenderIn(studentId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", studentId));

        long present = attendance.countByStudentIdAndAttendanceDateBetweenAndStatus(
                student.getId(), from, to, AttendanceStatus.PRESENT);
        long absent = attendance.countByStudentIdAndAttendanceDateBetweenAndStatus(
                student.getId(), from, to, AttendanceStatus.ABSENT);

        return StudentAttendanceSummaryResponse.of(student.getId(), from, to, present, absent);
    }

    /**
     * Daily head counts over a window, for the trend chart.
     *
     * <p>Unmarked days are absent from the series rather than zero-filled, which is the
     * decision argued on {@link AttendanceTrendResponse}: the database returns only the
     * days it has rows for, and this method deliberately does not fill the gaps.
     */
    @Transactional(readOnly = true)
    public AttendanceTrendResponse trend(LocalDate from, LocalDate to) {
        AccessScope scope = currentUser.scope();
        requireWindow(from, to);

        List<AttendanceTrendResponse.Day> days = attendance
                .findDailyTotals(scope.visibleGenders(), from, to).stream()
                .map(AttendanceService::toDay)
                .toList();

        return new AttendanceTrendResponse(from, to, days);
    }

    private static AttendanceTrendResponse.Day toDay(AttendanceDayTotals totals) {
        return new AttendanceTrendResponse.Day(
                totals.date(), totals.present(), totals.absent(), totals.marked());
    }

    private static Attendance newMark(
            Student student, LocalDate date, AttendanceStatus status, UserAccount actor, Instant when) {
        Attendance mark = new Attendance();
        mark.setStudent(student);
        mark.setAttendanceDate(date);
        mark.setStatus(status);
        mark.setMarkedBy(actor);
        mark.setMarkedAt(when);
        return mark;
    }

    private void requireMarkableDate(LocalDate date) {
        LocalDate today = today();
        if (date.isAfter(today)) {
            throw new ApiException(ErrorCode.ATTENDANCE_DATE_INVALID,
                    "The register cannot be marked for a future date",
                    Map.of("attendanceDate", date.toString(), "today", today.toString()));
        }
    }

    private void requireWindow(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ApiException(ErrorCode.ATTENDANCE_DATE_INVALID,
                    "The start of the window is after its end",
                    Map.of("from", from.toString(), "to", to.toString()));
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_REPORT_DAYS) {
            throw new ApiException(ErrorCode.ATTENDANCE_DATE_INVALID,
                    "A window of at most " + MAX_REPORT_DAYS + " days may be reported on",
                    Map.of("requestedDays", days, "maxDays", MAX_REPORT_DAYS));
        }
    }

    /**
     * Today, in UTC.
     *
     * <p>Explicitly UTC rather than the JVM default so that "is this date in the future"
     * has the same answer as the {@code TIMESTAMPTZ} columns everything else is stored in.
     * A {@code Clock} bean would make this injectable, and nothing needs it to be: the
     * dates that matter to a test are the ones it passes in.
     */
    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private UserAccount actor() {
        Long userId = currentUser.require().getUserId();
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL, "Acting account not found"));
    }
}
