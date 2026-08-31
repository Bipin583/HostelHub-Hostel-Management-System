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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An invoice: what a student owes for one term.
 *
 * <p>Separate from {@link FeePayment} on purpose. The predecessor had one table
 * doing both jobs, which makes a part payment impossible to represent without
 * either editing the amount owed (losing what was originally billed) or inserting a
 * second charge (inventing money the student was never billed). One invoice, many
 * attempts to pay it, and the sum of the successful ones is the only thing that
 * moves {@link #amountPaidPaise}.
 *
 * <p>Amounts are integer paise, never {@code double}. Currency arithmetic in binary
 * floating point loses money in the last decimal place, and a fee ledger is exactly
 * where that is least acceptable.
 */
@Entity
@Table(name = "hostel_fees")
@Getter
@Setter
@NoArgsConstructor
public class HostelFee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "academic_year", nullable = false, length = 20)
    private String academicYear;

    @Column(nullable = false, length = 20)
    private String semester;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(name = "amount_paid_paise", nullable = false)
    private Long amountPaidPaise = 0L;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FeeStatus status = FeeStatus.UNPAID;

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

    /** What is still owed, in paise. Never negative. */
    public long outstandingPaise() {
        if (this.status == FeeStatus.CANCELLED) {
            return 0L;
        }
        return Math.max(0L, this.amountPaise - this.amountPaidPaise);
    }

    public boolean isSettled() {
        return this.status == FeeStatus.PAID || this.status == FeeStatus.CANCELLED;
    }

    public boolean isOverdue(LocalDate today) {
        return !isSettled() && this.dueDate.isBefore(today);
    }

    /**
     * Credits a settled payment against this invoice.
     *
     * <p>Rejects an overpayment rather than clamping it. The database would reject it
     * too ({@code ck_hostel_fees_paid}), but a clean exception here names the actual
     * problem instead of surfacing a constraint name, and the service turns it into a
     * 400 before any money has been captured.
     *
     * <p>This method assumes the caller holds the row -- the payment service takes a
     * pessimistic lock on the invoice before calling it. Two concurrent payments
     * without that lock would both read the same {@code amountPaidPaise}, and the
     * second write would erase the first: the fee-ledger form of the double-booking
     * race, with the same fix.
     *
     * <p>The overpayment refusal above is not a substitute for that lock, and it is not
     * even a partial one where it looks like it should be. It stops one payment being
     * credited twice only when the payment is for the whole outstanding balance; a part
     * payment redelivered concurrently leaves room, so the second credit is legitimate
     * arithmetic and passes. That is why the service locks the payment row as well as
     * this one -- {@code docs/concurrency.md} §3.
     */
    public void applyPayment(long paise) {
        if (paise <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive: " + paise);
        }
        if (this.status == FeeStatus.CANCELLED) {
            throw new IllegalStateException("Cannot pay a cancelled fee: " + this.id);
        }
        long updated = this.amountPaidPaise + paise;
        if (updated > this.amountPaise) {
            throw new IllegalArgumentException(
                    "Payment of %d paise exceeds the %d paise outstanding on fee %d"
                            .formatted(paise, outstandingPaise(), this.id));
        }
        this.amountPaidPaise = updated;
        recalculateStatus();
    }

    /**
     * Writes the invoice off.
     *
     * <p>Kept rather than deleted: a cancelled fee that has part payments against it
     * still has to explain where that money went, and a deleted row cannot.
     */
    public void cancel() {
        if (this.status == FeeStatus.PAID) {
            throw new IllegalStateException("Cannot cancel a fully paid fee: " + this.id);
        }
        this.status = FeeStatus.CANCELLED;
    }

    /**
     * Derives {@link #status} from the amounts.
     *
     * <p>The one place that decides what the status column means, so the stored value
     * and the arithmetic cannot disagree. {@code CANCELLED} is left alone -- it is a
     * decision, not a function of the balance.
     */
    private void recalculateStatus() {
        if (this.status == FeeStatus.CANCELLED) {
            return;
        }
        if (this.amountPaidPaise >= this.amountPaise) {
            this.status = FeeStatus.PAID;
        } else if (this.amountPaidPaise > 0) {
            this.status = FeeStatus.PARTIALLY_PAID;
        } else {
            this.status = FeeStatus.UNPAID;
        }
    }
}
