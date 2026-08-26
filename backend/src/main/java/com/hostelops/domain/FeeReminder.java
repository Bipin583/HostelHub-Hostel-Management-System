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
 * The record that a reminder went out for one invoice on one day.
 *
 * <p>This table exists to make the scheduled reminder job idempotent, and the whole
 * guarantee is one line of DDL: {@code uq_fee_reminders_per_day UNIQUE (fee_id,
 * reminder_date)}. The job inserts the row and sends the message; a second run on
 * the same day hits the constraint and skips. Nothing depends on the job holding
 * state, on it completing, or on there being only one instance of it.
 *
 * <p>That matters most for the case a code-level guard handles worst: a restart
 * halfway through the run. A job that tracked "last run date" in a field, or in a
 * single settings row, would either resend everything or resend nothing after a
 * crash at fee 400 of 900. Per-fee rows make the answer per fee -- the 400 already
 * sent stay sent, the remaining 500 go out on the retry.
 *
 * <p>The insert is therefore deliberately ordered <em>before</em> the send. A
 * duplicate reminder is a small annoyance; the ordering makes it impossible at the
 * cost of a message that is occasionally lost when delivery fails after the row is
 * written. For a payment reminder with a due date weeks out and a daily job, that is
 * the right side of the trade -- see {@code docs/concurrency.md}.
 */
@Entity
@Table(name = "fee_reminders")
@Getter
@Setter
@NoArgsConstructor
public class FeeReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fee_id", nullable = false)
    private HostelFee fee;

    /**
     * The day the reminder covers, not the instant it was sent.
     *
     * <p>A {@code DATE} rather than a timestamp because "once a day" is the rule being
     * enforced, and a unique constraint on a timestamp would enforce nothing.
     */
    @Column(name = "reminder_date", nullable = false)
    private LocalDate reminderDate;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReminderChannel channel = ReminderChannel.EMAIL;

    @PrePersist
    void onCreate() {
        if (this.sentAt == null) {
            this.sentAt = Instant.now();
        }
    }

    public static FeeReminder forFeeOn(HostelFee fee, LocalDate date, ReminderChannel channel) {
        FeeReminder reminder = new FeeReminder();
        reminder.setFee(fee);
        reminder.setReminderDate(date);
        reminder.setChannel(channel);
        return reminder;
    }
}
