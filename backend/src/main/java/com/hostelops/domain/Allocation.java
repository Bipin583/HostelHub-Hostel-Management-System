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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A student's occupancy of a room, current or historical.
 *
 * <p>Vacating flips {@link #active} to false and stamps {@link #vacatedAt}
 * rather than deleting the row, so residency history survives. A partial unique
 * index ({@code uq_allocations_active_student}) permits many inactive rows per
 * student but only one active one.
 */
@Entity
@Table(name = "allocations")
@Getter
@Setter
@NoArgsConstructor
public class Allocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "allocated_at", nullable = false, updatable = false)
    private Instant allocatedAt;

    /** Null when the allocation was made by the auto-matcher rather than a person. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allocated_by")
    private UserAccount allocatedBy;

    @Column(name = "vacated_at")
    private Instant vacatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vacated_by")
    private UserAccount vacatedBy;

    @PrePersist
    void onCreate() {
        if (this.allocatedAt == null) {
            this.allocatedAt = Instant.now();
        }
    }

    /** Release the bed, keeping the row as history. */
    public void vacate(UserAccount actor, Instant when) {
        this.active = false;
        this.vacatedAt = when;
        this.vacatedBy = actor;
    }
}
