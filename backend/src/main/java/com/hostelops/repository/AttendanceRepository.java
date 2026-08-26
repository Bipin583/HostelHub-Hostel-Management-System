package com.hostelops.repository;

import com.hostelops.domain.Attendance;
import com.hostelops.domain.Gender;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Attendance queries.
 *
 * <p>Same rule as {@link StudentRepository}: no unscoped finder is inherited, so a
 * collection method that forgot the caller's permitted genders would not compile.
 */
public interface AttendanceRepository extends Repository<Attendance, Long> {

    Attendance save(Attendance attendance);

    Attendance saveAndFlush(Attendance attendance);

    /**
     * Saves a whole register.
     *
     * <p>Present so that a two-hundred-student submission is one call into the
     * persistence context rather than two hundred, which lets Hibernate's JDBC batching
     * turn it into a handful of round trips.
     */
    List<Attendance> saveAll(Iterable<Attendance> marks);

    /**
     * Forces the pending inserts out to the database.
     *
     * <p>The batch equivalent of {@link #saveAndFlush}: {@code uq_attendance_student_date}
     * has to fire while {@code AttendanceService} is still on the stack, where the
     * violation can be turned into a 409 that names the student, rather than at commit
     * after the method has returned an apparently successful response.
     */
    void flush();

    Optional<Attendance> findById(Long id);

    /**
     * The existing mark for a student on a date, if the register was taken.
     *
     * <p>Deliberately unscoped: only ever called with a student id that a scoped
     * lookup has already returned. Backed by {@code uq_attendance_student_date}, so
     * the query is an index lookup and the same constraint stops a concurrent
     * duplicate insert if two wardens mark the register at once.
     */
    Optional<Attendance> findByStudentIdAndAttendanceDate(Long studentId, LocalDate date);

    /**
     * The marks that already exist for a set of students on one date.
     *
     * <p>The batch form of the method above, and the reason a bulk register submission
     * issues two queries instead of two hundred: one to find which students already have a
     * row for the date, then one write per row that actually changes.
     *
     * <p>Unscoped for the same reason as the single-row version -- the ids handed to it
     * have already come back from a scoped student lookup.
     */
    List<Attendance> findByStudentIdInAndAttendanceDate(Collection<Long> studentIds, LocalDate date);

    /**
     * A student's marks over a window, newest first.
     *
     * <p>Uses {@code idx_attendance_student_date}, which is declared
     * {@code (student_id, attendance_date DESC)} so this ordering is read straight off
     * the index rather than sorted afterwards.
     *
     * <p>The entity graph is what {@code AttendanceMapper} relies on. Without it, reading
     * the marking warden's name off each row lazy-loads one account per day of history --
     * the N+1 that mapper's Javadoc refuses to paper over. {@code left join} semantics
     * come free with an entity graph, which matters because {@code marked_by} is nullable.
     */
    @EntityGraph(attributePaths = {"student", "student.user", "markedBy"})
    List<Attendance> findByStudentIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(
            Long studentId, LocalDate from, LocalDate to);

    long countByStudentIdAndAttendanceDateBetweenAndStatus(
            Long studentId, LocalDate from, LocalDate to, com.hostelops.domain.AttendanceStatus status);

    /**
     * The register for one date, for every student the caller may see.
     *
     * <p>Fetch-joins the student and their account: a register page shows names, and
     * lazily loading each one is the N+1 that turns a 200-row page into 401 queries.
     * {@code markedBy} is joined for the same reason and with a {@code left join}, since a
     * row whose marking warden has since been deleted still has to appear on the page.
     *
     * <p>All three joins are to-one, so pagination stays in SQL. A fetch join to a
     * collection would have forced Hibernate to page in memory -- which is the version of
     * this query that reads every attendance row in the table to return twenty.
     */
    @Query(value = """
            select a from Attendance a
            join fetch a.student s
            join fetch s.user u
            left join fetch a.markedBy m
            where a.attendanceDate = :date and s.gender in :genders
            order by s.rollNumber asc
            """,
            countQuery = """
                    select count(a) from Attendance a
                    where a.attendanceDate = :date and a.student.gender in :genders
                    """)
    Page<Attendance> findRegisterFor(
            @Param("date") LocalDate date,
            @Param("genders") Collection<Gender> genders,
            Pageable pageable);

    /**
     * Every mark since a date, as flat rows.
     *
     * <p>Feeds the absence-streak detector, which needs one pass over recent history
     * for every student. A constructor expression rather than entities on purpose:
     * hydrating tens of thousands of {@code Attendance} instances (each with a lazy
     * proxy to a student) to read three fields off them is the expensive way to run a
     * nightly job.
     *
     * <p>Unscoped because the scan is a system job with no caller. The window is what
     * bounds it -- see {@code AbsenceAlertService}, which reads back only far enough to
     * establish the current streak.
     */
    @Query("""
            select new com.hostelops.repository.AttendanceMarkRow(
                a.student.id, a.attendanceDate, a.status)
            from Attendance a
            where a.attendanceDate >= :from
            order by a.student.id asc, a.attendanceDate desc
            """)
    List<AttendanceMarkRow> findMarksSince(@Param("from") LocalDate from);

    /**
     * Present/absent totals per day over a window, aggregated in the database.
     *
     * <p>{@code count(case when ...)} rather than two queries or a Java-side group-by:
     * the trend chart wants one row per day and the database is where that reduction
     * belongs. Uses {@code idx_attendance_date_status}.
     */
    @Query("""
            select new com.hostelops.repository.AttendanceDayTotals(
                a.attendanceDate,
                count(case when a.status = com.hostelops.domain.AttendanceStatus.PRESENT then 1 end),
                count(case when a.status = com.hostelops.domain.AttendanceStatus.ABSENT then 1 end))
            from Attendance a
            where a.student.gender in :genders
              and a.attendanceDate between :from and :to
            group by a.attendanceDate
            order by a.attendanceDate asc
            """)
    List<AttendanceDayTotals> findDailyTotals(
            @Param("genders") Collection<Gender> genders,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
