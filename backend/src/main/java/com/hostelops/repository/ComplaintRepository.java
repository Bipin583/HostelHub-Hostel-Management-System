package com.hostelops.repository;

import com.hostelops.domain.Complaint;
import com.hostelops.domain.ComplaintStatus;
import com.hostelops.domain.Gender;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ComplaintRepository extends Repository<Complaint, Long> {

    Complaint save(Complaint complaint);

    Complaint saveAndFlush(Complaint complaint);

    /**
     * A complaint the caller is allowed to work on.
     *
     * <p>The gender filter is the warden's scope, applied in the same statement that
     * fetches the row rather than checked afterwards. A fetch-then-check-then-403 shape
     * confirms which ids exist; this one returns empty, and the service turns that into
     * a 404.
     */
    Optional<Complaint> findByIdAndStudentGenderIn(Long id, Collection<Gender> genders);

    /** A complaint on the student's own list. */
    Optional<Complaint> findByIdAndStudentId(Long id, Long studentId);

    /**
     * The warden's queue, whole.
     *
     * <p>Ordered by urgency then age. Urgency is the student's claim rather than a
     * verified fact (see {@code ComplaintUrgency}), so it puts the loudest first, and
     * the {@code createdAt} tiebreak means a quiet complaint still surfaces as it ages
     * instead of sitting behind every new CRITICAL forever.
     *
     * <p>Sorting on the enum's stored text would give alphabetical order -- CRITICAL,
     * HIGH, LOW, MEDIUM -- which is not severity, so the rank is spelled out.
     */
    @Query(value = """
            select c from Complaint c
            join fetch c.student s
            join fetch s.user u
            left join fetch c.resolvedBy rb
            where s.gender in :genders
            order by case c.urgency
                        when com.hostelops.domain.ComplaintUrgency.CRITICAL then 0
                        when com.hostelops.domain.ComplaintUrgency.HIGH then 1
                        when com.hostelops.domain.ComplaintUrgency.MEDIUM then 2
                        else 3
                     end asc,
                     c.createdAt asc
            """,
            countQuery = """
                    select count(c) from Complaint c
                    where c.student.gender in :genders
                    """)
    Page<Complaint> findInScope(@Param("genders") Collection<Gender> genders, Pageable pageable);

    /**
     * The same queue, narrowed to one status.
     *
     * <p>A separate method rather than a nullable {@code :status} parameter. An
     * {@code (:status is null or ...)} predicate reads as one query and is two: the
     * planner sees an expression it cannot use an index for, and the untyped null makes
     * the enum binding ambiguous. Two named methods cost four lines and are honest
     * about being two queries.
     */
    @Query(value = """
            select c from Complaint c
            join fetch c.student s
            join fetch s.user u
            left join fetch c.resolvedBy rb
            where s.gender in :genders and c.status = :status
            order by case c.urgency
                        when com.hostelops.domain.ComplaintUrgency.CRITICAL then 0
                        when com.hostelops.domain.ComplaintUrgency.HIGH then 1
                        when com.hostelops.domain.ComplaintUrgency.MEDIUM then 2
                        else 3
                     end asc,
                     c.createdAt asc
            """,
            countQuery = """
                    select count(c) from Complaint c
                    where c.student.gender in :genders and c.status = :status
                    """)
    Page<Complaint> findInScopeByStatus(
            @Param("genders") Collection<Gender> genders,
            @Param("status") ComplaintStatus status,
            Pageable pageable);

    /** The student's own complaints, newest first. Uses {@code idx_complaints_student}. */
    @Query(value = """
            select c from Complaint c
            left join fetch c.resolvedBy rb
            where c.student.id = :studentId
            order by c.createdAt desc
            """,
            countQuery = "select count(c) from Complaint c where c.student.id = :studentId")
    Page<Complaint> findForStudent(@Param("studentId") Long studentId, Pageable pageable);

    long countByStudentGenderInAndStatus(Collection<Gender> genders, ComplaintStatus status);

    long countByStudentIdAndStatusNot(Long studentId, ComplaintStatus status);

    /**
     * Complaint counts by category and status for the caller's students.
     *
     * <p>Grouped in the database. Loading every complaint to count them in Java is a
     * full table read to produce two dozen numbers, and it gets slower every term.
     */
    @Query("""
            select new com.hostelops.repository.ComplaintCategoryTotals(
                c.category, c.status, count(c))
            from Complaint c
            where c.student.gender in :genders
            group by c.category, c.status
            """)
    List<ComplaintCategoryTotals> findCategoryTotals(@Param("genders") Collection<Gender> genders);

    /**
     * Resolution timings, in seconds, for complaints resolved since {@code from}.
     *
     * <p>Native SQL because the interesting values are interval arithmetic --
     * {@code resolved_at - created_at} -- which JPQL has no portable way to average.
     * Split into the queue wait and the work time because those are the two numbers a
     * warden can act on separately; a single "time to resolution" hides which of them is
     * the problem.
     *
     * <p>The percentile matters more than the mean: one complaint left open over the
     * summer break drags an average past usefulness, so p90 is reported alongside it.
     * {@code percentile_cont} is an ordered-set aggregate, exactly the kind of work
     * worth doing in the database rather than shipping rows out to sort.
     *
     * <p>Genders bind as text -- enums in native parameter lists are ambiguous between
     * name and ordinal, and this column stores the name.
     */
    @Query(value = """
            SELECT count(*)                                                        AS resolvedCount,
                   avg(EXTRACT(EPOCH FROM (c.resolved_at - c.created_at)))         AS avgTotalSeconds,
                   avg(EXTRACT(EPOCH FROM (c.in_progress_at - c.created_at)))      AS avgQueueSeconds,
                   avg(EXTRACT(EPOCH FROM (c.resolved_at - c.in_progress_at)))     AS avgWorkSeconds,
                   percentile_cont(0.9) WITHIN GROUP (
                       ORDER BY EXTRACT(EPOCH FROM (c.resolved_at - c.created_at))) AS p90TotalSeconds
            FROM complaints c
            JOIN students s ON s.id = c.student_id
            WHERE c.resolved_at IS NOT NULL
              AND c.resolved_at >= :from
              AND s.gender IN (:genders)
            """, nativeQuery = true)
    ComplaintResolutionRow findResolutionStats(
            @Param("genders") Collection<String> genders, @Param("from") Instant from);

    /**
     * How many complaints are still open, and how old the oldest is.
     *
     * <p>Reported next to the resolution average because the average only covers
     * complaints that were resolved. A team that closes the easy ones quickly and never
     * touches the hard ones has an excellent average and a growing backlog; this is the
     * number that shows it.
     */
    @Query(value = """
            SELECT count(*)                                                     AS openCount,
                   coalesce(max(EXTRACT(EPOCH FROM (now() - c.created_at))), 0)  AS oldestOpenSeconds
            FROM complaints c
            JOIN students s ON s.id = c.student_id
            WHERE c.status <> 'RESOLVED'
              AND s.gender IN (:genders)
            """, nativeQuery = true)
    ComplaintBacklogRow findBacklog(@Param("genders") Collection<String> genders);

    /** Projection for {@link #findResolutionStats}. Averages are null when nothing was resolved. */
    interface ComplaintResolutionRow {

        long getResolvedCount();

        Double getAvgTotalSeconds();

        Double getAvgQueueSeconds();

        Double getAvgWorkSeconds();

        Double getP90TotalSeconds();
    }

    /** Projection for {@link #findBacklog}. */
    interface ComplaintBacklogRow {

        long getOpenCount();

        double getOldestOpenSeconds();
    }
}
