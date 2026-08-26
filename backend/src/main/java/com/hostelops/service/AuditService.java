package com.hostelops.service;

import com.hostelops.dto.audit.AuditEventResponse;
import com.hostelops.mapper.AuditMapper;
import com.hostelops.repository.AuditEventRepository;
import java.time.Instant;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the audit trail.
 *
 * <p>Read-only, all of it. Writing is {@code AuditAspect}'s job and nothing else's, which
 * is why this class has no {@code record} method for a caller to reach for -- a service
 * able to append to the trail is a service able to append a lie to it, and the whole value
 * of the table is that its rows appeared as a side effect of the thing they describe.
 *
 * <p>No {@code AccessScope} either, unlike every other service here. That is the
 * authorization decision argued on {@code AuditEventRepository}: a trail narrowed to the
 * reader's own hostel cannot answer "did a warden touch a record outside their scope",
 * which is most of what it is for. Access is gated at the route instead --
 * {@code /api/v1/admin/**} requires ADMIN -- so this class is only ever reached by someone
 * entitled to see everything.
 */
@Service
public class AuditService {

    private final AuditEventRepository events;
    private final AuditMapper auditMapper;

    public AuditService(AuditEventRepository events, AuditMapper auditMapper) {
        this.events = events;
        this.auditMapper = auditMapper;
    }

    /** The activity feed: everything, newest first. */
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> recent(Pageable pageable) {
        return events.findRecent(pageable).map(auditMapper::toResponse);
    }

    /**
     * One record's history.
     *
     * <p>The type is upper-cased before matching, so a hand-typed
     * {@code ?entityType=allocation} finds the rows {@link com.hostelops.audit.AuditEntity}
     * wrote. An unrecognised type returns an empty page rather than a 400: the table is
     * append-only and older rows may name a type this build no longer defines, so
     * validating the query against today's constants would hide exactly the history
     * somebody went looking for.
     */
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> forEntity(String entityType, Long entityId, Pageable pageable) {
        String type = entityType == null ? "" : entityType.trim().toUpperCase(Locale.ROOT);
        return events.findForEntity(type, entityId, pageable).map(auditMapper::toResponse);
    }

    /**
     * One person's activity.
     *
     * <p>Cannot show what the schedulers did: their events have no actor, by design. The
     * feed from {@link #recent} is where a job's work shows up.
     */
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> forActor(Long actorId, Pageable pageable) {
        return events.findForActor(actorId, pageable).map(auditMapper::toResponse);
    }

    /** How much has happened lately, for the admin dashboard's activity counter. */
    @Transactional(readOnly = true)
    public long countSince(Instant after) {
        return events.countByCreatedAtAfter(after);
    }
}
