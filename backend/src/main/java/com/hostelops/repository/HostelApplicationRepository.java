package com.hostelops.repository;

import com.hostelops.domain.ApplicationStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelApplication;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Applications for accommodation.
 *
 * <p>Every finder that can return more than the caller's own row takes a
 * {@code genders} argument, for the reason spelled out on
 * {@link StudentRepository}. The fetch joins are not decoration: an application
 * response carries the applicant's name and the deciding warden's, both behind
 * lazy associations, and a page of twenty without them is forty-one queries.
 *
 * <p>{@code decidedBy} is a <em>left</em> join fetch throughout, because a pending
 * application has not been decided by anyone and an inner join would silently drop
 * exactly the rows a warden's queue is made of.
 */
public interface HostelApplicationRepository extends Repository<HostelApplication, Long> {

    HostelApplication save(HostelApplication application);

    Optional<HostelApplication> findById(Long id);

    Optional<HostelApplication> findByStudentIdAndStatus(Long studentId, ApplicationStatus status);

    boolean existsByStudentIdAndStatus(Long studentId, ApplicationStatus status);

    /** Scoped single fetch: another hostel's application comes back empty, hence 404. */
    @Query("""
            select a from HostelApplication a
            join fetch a.student s
            join fetch s.user u
            left join fetch a.decidedBy d
            where a.id = :id and s.gender in :genders
            """)
    Optional<HostelApplication> findByIdInScope(
            @Param("id") Long id, @Param("genders") Collection<Gender> genders);

    @Query(value = """
            select a from HostelApplication a
            join fetch a.student s
            join fetch s.user u
            left join fetch a.decidedBy d
            where a.status = :status and s.gender in :genders
            order by a.appliedAt asc
            """,
            countQuery = """
                    select count(a) from HostelApplication a
                    where a.status = :status and a.student.gender in :genders
                    """)
    Page<HostelApplication> findByStatusInScope(
            @Param("status") ApplicationStatus status,
            @Param("genders") Collection<Gender> genders,
            Pageable pageable);

    /**
     * Every application in scope, newest first.
     *
     * <p>Ordered the opposite way from the pending queue on purpose: a queue is
     * worked oldest-first so nobody waits forever, whereas a history is read
     * newest-first.
     */
    @Query(value = """
            select a from HostelApplication a
            join fetch a.student s
            join fetch s.user u
            left join fetch a.decidedBy d
            where s.gender in :genders
            order by a.appliedAt desc
            """,
            countQuery = """
                    select count(a) from HostelApplication a
                    where a.student.gender in :genders
                    """)
    Page<HostelApplication> findAllInScope(
            @Param("genders") Collection<Gender> genders, Pageable pageable);

    /**
     * One student's own application history.
     *
     * <p>Unscoped by gender and safe for the same reason as
     * {@code StudentRepository#findByUserId}: the only id passed here is the
     * caller's own, taken from the token.
     */
    @Query("""
            select a from HostelApplication a
            join fetch a.student s
            join fetch s.user u
            left join fetch a.decidedBy d
            where s.id = :studentId
            order by a.appliedAt desc
            """)
    List<HostelApplication> findHistoryForStudent(@Param("studentId") Long studentId);

    long countByStatusAndStudentGenderIn(ApplicationStatus status, Collection<Gender> genders);
}
