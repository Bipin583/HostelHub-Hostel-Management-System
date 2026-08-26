package com.hostelops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A room, with a bed {@link #capacity} but no occupancy counter.
 *
 * <p>Occupancy is always {@code count(active allocations)}. A cached counter
 * column is what let the predecessor report a full room that had free beds, so
 * there is nothing here to fall out of step.
 *
 * <p>No {@code @Version} field: allocation serialises on this row with
 * {@code SELECT ... FOR UPDATE} (see {@code RoomRepository#findByIdForUpdate}).
 * Optimistic locking was the alternative; the tradeoff is written up in
 * {@code docs/concurrency.md}.
 */
@Entity
@Table(name = "rooms")
@Getter
@Setter
@NoArgsConstructor
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The room's only identifier. Not aliased as "room_number" anywhere. */
    @Column(name = "room_name", nullable = false, length = 20)
    private String roomName;

    @Enumerated(EnumType.STRING)
    @Column(name = "hostel_type", nullable = false, length = 2)
    private HostelType hostelType;

    @Column(nullable = false, length = 20)
    private String block;

    @Column(nullable = false)
    private Integer floor;

    @Column(nullable = false)
    private Integer capacity;

    @Column(name = "eligible_year", nullable = false)
    private Integer eligibleYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "eligible_gender", nullable = false, length = 1)
    private Gender eligibleGender;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** True when this room is eligible for the given student on paper. */
    public boolean matches(Student student) {
        return this.eligibleGender == student.getGender()
                && this.eligibleYear.equals(student.getYearOfStudy());
    }
}
