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
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One student's presence on one day.
 *
 * <p>A row per student per day, guarded by {@code uq_attendance_student_date}. The
 * alternative -- storing only absences -- makes "was this day marked at all?"
 * unanswerable, and the absence detector needs that distinction: a gap in the
 * register because nobody took the roll is not a run of absences.
 *
 * <p>{@code markedBy} is nullable and cleared on user deletion ({@code ON DELETE
 * SET NULL}), because the attendance fact outlives the warden's account. Losing
 * who marked it is acceptable; losing whether the student was there is not.
 */
@Entity
@Table(name = "attendance")
@Getter
@Setter
@NoArgsConstructor
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AttendanceStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marked_by")
    private UserAccount markedBy;

    @Column(name = "marked_at", nullable = false)
    private Instant markedAt;

    @PrePersist
    void onCreate() {
        if (this.markedAt == null) {
            this.markedAt = Instant.now();
        }
    }

    /**
     * Overwrites an existing mark.
     *
     * <p>Corrections are expected -- a student who signs in late was recorded
     * absent an hour ago -- so the row is updated rather than duplicated, and
     * {@code markedAt} moves to the correction. The original value is not kept
     * here; the audit trail is where "this was flipped, by whom" lives.
     */
    public void remark(AttendanceStatus newStatus, UserAccount actor, Instant when) {
        this.status = newStatus;
        this.markedBy = actor;
        this.markedAt = when;
    }
}
