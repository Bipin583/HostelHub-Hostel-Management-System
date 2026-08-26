package com.hostelops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A raised alert that a student has been absent for a run of working days.
 *
 * <p>The predecessor answered "who has been absent 10+ days?" with a live query
 * over the attendance table. That reports the present tense and nothing else: a
 * student absent for three weeks in September who returned in October leaves no
 * trace, so nobody can ask whether the alert was ever acted on, or how often this
 * happens, or how long alerts sit unacknowledged. Persisting the alert turns a
 * dashboard number into a record with a history.
 *
 * <p>Identity is {@code (student_id, streak_start_date)} via
 * {@code uq_absence_alert_streak}, which is what makes the detector idempotent:
 * re-running it on an ongoing streak raises the day count on the existing row
 * rather than creating a second alert for the same absence. Restarting the job
 * mid-scan, or running two instances, therefore cannot double-alert.
 */
@Entity
@Table(name = "absence_alerts")
@Getter
@Setter
@NoArgsConstructor
public class AbsenceAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    /** First working day of the run. The stable half of the alert's identity. */
    @Column(name = "streak_start_date", nullable = false)
    private LocalDate streakStartDate;

    /** Working days absent, as of the last scan. Grows while the streak continues. */
    @Column(name = "consecutive_days", nullable = false)
    private Integer consecutiveDays;

    /** The scan date that first crossed the threshold. Never moves afterwards. */
    @Column(name = "triggered_on", nullable = false)
    private LocalDate triggeredOn;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acknowledged_by")
    private UserAccount acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    /**
     * Records that the streak has grown.
     *
     * <p>{@code triggeredOn} deliberately does not move. The alert's age is measured
     * from when it was first raised, so an alert nobody has looked at for a fortnight
     * cannot make itself look fresh by the student staying away.
     */
    public void extendTo(int days) {
        this.consecutiveDays = days;
    }

    public boolean isAcknowledged() {
        return this.acknowledgedAt != null;
    }

    /**
     * Marks the alert as handled by a member of staff.
     *
     * <p>Acknowledging is not resolving: it says somebody has seen this, not that
     * the student is back. The streak keeps growing in {@code consecutiveDays}
     * either way, which is why the open-alerts index is partial on
     * {@code acknowledged_at IS NULL} rather than on the day count.
     */
    public void acknowledge(UserAccount actor, Instant when) {
        this.acknowledgedBy = actor;
        this.acknowledgedAt = when;
    }

    public void markNotified(Instant when) {
        this.notifiedAt = when;
    }
}
