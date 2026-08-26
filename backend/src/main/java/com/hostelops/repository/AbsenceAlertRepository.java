package com.hostelops.repository;

import com.hostelops.domain.AbsenceAlert;
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

public interface AbsenceAlertRepository extends Repository<AbsenceAlert, Long> {

    AbsenceAlert save(AbsenceAlert alert);

    AbsenceAlert saveAndFlush(AbsenceAlert alert);

    /**
     * The alert for one streak, if it has already been raised.
     *
     * <p>The detector's idempotency check. It is the read half of the pair whose write
     * half is {@code uq_absence_alert_streak}: the lookup avoids the exception in the
     * common case, the constraint is what makes the guarantee true when two scans
     * overlap. Doing only the lookup would leave a window; doing only the insert would
     * make the normal path throw.
     */
    Optional<AbsenceAlert> findByStudentIdAndStreakStartDate(Long studentId, LocalDate streakStart);

    /** Every open alert for a student, used to close stale ones when they return. */
    List<AbsenceAlert> findByStudentIdAndAcknowledgedAtIsNull(Long studentId);

    /**
     * Every open alert in the system, students attached.
     *
     * <p>Unscoped and unpaged, because the caller is the nightly scan rather than a
     * person: it needs the whole set at once, since an open alert is the only record of
     * where a streak began once that date falls outside the window of marks the scan
     * reads back. Loading them one student at a time would be one query per absent
     * student on a job that already reads the register in a single pass.
     *
     * <p>Unpaged is safe because the row count is bounded by something an office
     * controls -- alerts stay open only until somebody acknowledges them, and a hostel
     * with thousands of unacknowledged alerts has a problem this query is not the cause
     * of. The entity graph fetches the student and their account, which the scan needs to
     * address a notification and would otherwise lazy-load one alert at a time.
     */
    @EntityGraph(attributePaths = {"student", "student.user"})
    List<AbsenceAlert> findByAcknowledgedAtIsNull();

    Optional<AbsenceAlert> findByIdAndStudentGenderIn(Long id, Collection<Gender> genders);

    /**
     * Open alerts for the caller's students, oldest first.
     *
     * <p>Oldest first because an alert that has sat unacknowledged for a fortnight is
     * the one that matters, and a newest-first queue buries it. Uses the partial index
     * {@code idx_absence_alerts_open}, which only contains unacknowledged rows -- so the
     * index stays small as the historical table grows.
     */
    @Query(value = """
            select al from AbsenceAlert al
            join fetch al.student s
            join fetch s.user u
            where al.acknowledgedAt is null and s.gender in :genders
            order by al.triggeredOn asc, al.id asc
            """,
            countQuery = """
                    select count(al) from AbsenceAlert al
                    where al.acknowledgedAt is null and al.student.gender in :genders
                    """)
    Page<AbsenceAlert> findOpenInScope(
            @Param("genders") Collection<Gender> genders, Pageable pageable);

    @Query(value = """
            select al from AbsenceAlert al
            join fetch al.student s
            join fetch s.user u
            left join fetch al.acknowledgedBy ack
            where s.gender in :genders
            order by al.triggeredOn desc, al.id desc
            """,
            countQuery = """
                    select count(al) from AbsenceAlert al
                    where al.student.gender in :genders
                    """)
    Page<AbsenceAlert> findAllInScope(
            @Param("genders") Collection<Gender> genders, Pageable pageable);

    long countByAcknowledgedAtIsNullAndStudentGenderIn(Collection<Gender> genders);
}
