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
 * A request for accommodation: the first half of
 * {@code NOT_APPLIED -> PENDING -> ALLOCATED}.
 *
 * <p>Named {@code HostelApplication} so it cannot be confused with Spring's own
 * {@code Application} types. The table is {@code applications}.
 */
@Entity
@Table(name = "applications")
@Getter
@Setter
@NoArgsConstructor
public class HostelApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationStatus status = ApplicationStatus.PENDING;

    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private UserAccount decidedBy;

    @Column(columnDefinition = "text")
    private String note;

    @PrePersist
    void onCreate() {
        if (this.appliedAt == null) {
            this.appliedAt = Instant.now();
        }
    }

    /**
     * Record a decision. {@code decided_at} is stamped here because the schema
     * requires it to be present for any non-pending status.
     */
    public void decide(ApplicationStatus outcome, UserAccount actor, Instant when, String reason) {
        if (outcome == ApplicationStatus.PENDING) {
            throw new IllegalArgumentException("PENDING is not a decision");
        }
        this.status = outcome;
        this.decidedBy = actor;
        this.decidedAt = when;
        this.note = reason;
    }
}
