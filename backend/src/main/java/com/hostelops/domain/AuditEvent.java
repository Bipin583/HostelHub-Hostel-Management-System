package com.hostelops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One recorded change to something that matters.
 *
 * <p>Written by {@code AuditAspect} around service methods carrying
 * {@code @Audited}, not by a call at each mutation site. That is the whole design
 * decision: a new endpoint that allocates a room cannot forget to log it, because
 * logging is not something the endpoint does.
 *
 * <p>{@link #entityId} is a plain {@code BIGINT} with no foreign key, and
 * {@link #entityType} a string rather than a discriminator enum. Both are
 * deliberate. An audit row must survive the deletion of the thing it describes --
 * "who deleted this allocation" is precisely the question that a cascade would erase
 * the answer to -- and a single polymorphic table cannot carry a real FK to five
 * different parents anyway. The actor FK is {@code ON DELETE SET NULL} for the same
 * reason: losing the name is better than losing the event.
 *
 * <p>{@link #payloadDiff} is JSONB rather than a text blob so the trail is
 * queryable: "every allocation whose room changed" is an operator on a JSONB column
 * and a full-table scan with regexes on text.
 */
@Entity
@Table(name = "audit_events")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /** Nullable: a failed create has no id to point at, and the attempt is still worth recording. */
    @Column(name = "entity_id")
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuditAction action;

    /**
     * Who did it. Null for anything the system did to itself -- the scheduled fee
     * reminder and absence scan have no user behind them, and inventing a "system"
     * account to satisfy a NOT NULL would be a login that exists.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private UserAccount actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_diff")
    private String payloadDiff;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public static AuditEvent of(
            String entityType, Long entityId, AuditAction action, UserAccount actor, String payloadDiff) {
        AuditEvent event = new AuditEvent();
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setAction(action);
        event.setActor(actor);
        event.setPayloadDiff(payloadDiff);
        return event;
    }
}
