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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A resident or applicant.
 *
 * <p>There is deliberately no {@code room} field here. The predecessor carried
 * {@code Student.allocated_room} alongside an {@code Allocation} table and the
 * two drifted apart, so a student could display a room they held no allocation
 * to. The current room is derived from the active {@link Allocation}, full stop.
 */
@Entity
@Table(name = "students")
@Getter
@Setter
@NoArgsConstructor
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserAccount user;

    @Column(name = "roll_number", nullable = false, length = 30, unique = true)
    private String rollNumber;

    /** {@code VARCHAR(1)} in the schema; see the note on {@code Notice.audienceGender}. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 1)
    private Gender gender;

    @Column(name = "year_of_study", nullable = false)
    private Integer yearOfStudy;

    @Column(length = 100)
    private String branch;

    @Column(name = "mobile_no", length = 10)
    private String mobileNo;

    @Column(name = "parent_mobile_no", length = 10)
    private String parentMobileNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_status", nullable = false, length = 20)
    private AllocationStatus allocationStatus = AllocationStatus.NOT_APPLIED;

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
}
