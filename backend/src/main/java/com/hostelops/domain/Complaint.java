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

/**
 * A maintenance issue raised by a student and worked by a warden.
 *
 * <p>The three lifecycle timestamps are the point of the table. A boolean
 * {@code resolved} flag would answer "is this done"; {@code createdAt},
 * {@code inProgressAt} and {@code resolvedAt} answer "how long did it sit in the
 * queue" and "how long did the work take" separately, and those two numbers have
 * different fixes -- more wardens versus more plumbers. The analytics endpoint
 * reads them directly rather than reconstructing durations from an event log.
 *
 * <p>Transitions go forward only. Reopening is a new complaint: mutating a resolved
 * row would either falsify its resolution time or need a second set of timestamps,
 * and a fresh row costs nothing and keeps the first one's history intact.
 */
@Entity
@Table(name = "complaints")
@Getter
@Setter
@NoArgsConstructor
public class Complaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ComplaintCategory category = ComplaintCategory.GENERAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ComplaintUrgency urgency = ComplaintUrgency.LOW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private ComplaintStatus status = ComplaintStatus.OPEN;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "in_progress_at")
    private Instant inProgressAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private UserAccount resolvedBy;

    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    /** Whether {@code next} is reachable from the current state. */
    public boolean canTransitionTo(ComplaintStatus next) {
        return this.status.canTransitionTo(next);
    }

    /**
     * Moves the complaint forward and stamps the matching timestamp.
     *
     * <p>Callers are expected to check {@link #canTransitionTo} first so they can
     * report a 409 with the right error code; the {@code IllegalStateException} here
     * is the backstop for anything that does not -- a future bulk tool, a job -- and
     * exists so an illegal move fails loudly rather than writing a row that
     * {@code ck_complaints_resolved_at} would then reject with a less specific
     * message.
     *
     * @param next   the target state
     * @param actor  the member of staff making the change; recorded only on resolution
     * @param note   resolution detail, ignored for the move to {@code IN_PROGRESS}
     * @param when   the transition time, passed in so a test can control the clock
     */
    public void transitionTo(ComplaintStatus next, UserAccount actor, String note, Instant when) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal complaint transition: " + this.status + " -> " + next);
        }
        switch (next) {
            case IN_PROGRESS -> this.inProgressAt = when;
            case RESOLVED -> {
                // A complaint fixed on sight never passed through IN_PROGRESS. Stamping
                // it here keeps "time spent working" computable for every resolved row
                // instead of leaving a NULL the analytics query has to special-case.
                if (this.inProgressAt == null) {
                    this.inProgressAt = when;
                }
                this.resolvedAt = when;
                this.resolvedBy = actor;
                this.resolutionNote = note;
            }
            case OPEN -> throw new IllegalStateException("A complaint cannot be moved back to OPEN");
        }
        this.status = next;
    }

    public boolean isResolved() {
        return this.status == ComplaintStatus.RESOLVED;
    }
}
