package com.hostelops.repository;

import com.hostelops.domain.AuditEvent;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The audit trail. Admin-only, and therefore the one repository here with no scope
 * parameter on its collection methods.
 *
 * <p>That is an authorization decision, not an oversight: an audit log filtered by the
 * reader's scope is an audit log that cannot answer "did a warden touch a record outside
 * their hostel", which is the main thing it is for. Access is gated at the route --
 * {@code /api/v1/admin/**} requires ADMIN -- rather than by narrowing the query.
 *
 * <p>There is no {@code delete} method and no update. Rows are written by the aspect and
 * read; an audit trail the application can edit is not evidence of anything.
 */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent save(AuditEvent event);

    Optional<AuditEvent> findById(Long id);

    /**
     * Everything, newest first. Backed by {@code idx_audit_recent}.
     *
     * <p>Left join on the actor because system events have none, and an inner join would
     * silently hide every scheduled job's work from the activity view.
     */
    @Query(value = """
            select e from AuditEvent e
            left join fetch e.actor a
            order by e.createdAt desc, e.id desc
            """,
            countQuery = "select count(e) from AuditEvent e")
    Page<AuditEvent> findRecent(Pageable pageable);

    /** One record's history. Backed by {@code idx_audit_entity}. */
    @Query(value = """
            select e from AuditEvent e
            left join fetch e.actor a
            where e.entityType = :entityType and e.entityId = :entityId
            order by e.createdAt desc, e.id desc
            """,
            countQuery = """
                    select count(e) from AuditEvent e
                    where e.entityType = :entityType and e.entityId = :entityId
                    """)
    Page<AuditEvent> findForEntity(
            @Param("entityType") String entityType,
            @Param("entityId") Long entityId,
            Pageable pageable);

    /** One person's activity. Backed by {@code idx_audit_actor}. */
    @Query(value = """
            select e from AuditEvent e
            left join fetch e.actor a
            where e.actor.id = :actorId
            order by e.createdAt desc, e.id desc
            """,
            countQuery = "select count(e) from AuditEvent e where e.actor.id = :actorId")
    Page<AuditEvent> findForActor(@Param("actorId") Long actorId, Pageable pageable);

    long countByCreatedAtAfter(Instant after);

    long countByEntityTypeAndEntityId(String entityType, Long entityId);
}
