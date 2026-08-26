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
 * One attempt to pay part or all of a {@link HostelFee}.
 *
 * <p>Rows are append-only in spirit: an attempt is created {@code PENDING} before
 * the gateway is contacted and then settled once, and failed attempts are kept.
 * Deleting them would make "this card was declined three times before it worked"
 * unanswerable, which is a support question that gets asked.
 *
 * <p>{@link #idempotencyKey} is supplied by the client (the {@code Idempotency-Key}
 * header) and carries a UNIQUE constraint. That is what makes a retried request --
 * the student's browser resending after a timeout, a mobile client on a flaky
 * connection -- return the original attempt instead of charging twice. Checking for
 * a duplicate in application code first would still leave the window between the
 * check and the insert; the constraint closes it.
 *
 * <p>{@link #provider} is stored per row rather than assumed globally, so switching
 * gateways does not retroactively relabel every historical payment as belonging to
 * the new one. Reconciling last year's settlements needs to know which provider they
 * actually went through.
 */
@Entity
@Table(name = "fee_payments")
@Getter
@Setter
@NoArgsConstructor
public class FeePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fee_id", nullable = false)
    private HostelFee fee;

    /**
     * Denormalised from {@code fee.student} so the student's payment history is one
     * index scan rather than a join, and so a warden-scoped query can filter payments
     * without walking through the invoice table.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    /** Which gateway handled this attempt. Free text, not an enum -- see the class Javadoc. */
    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_order_id", length = 120)
    private String providerOrderId;

    @Column(name = "provider_payment_id", length = 120)
    private String providerPaymentId;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    /**
     * Records a captured payment.
     *
     * <p>{@code completedAt} is set in the same call as the status, because
     * {@code ck_fee_payments_completed} requires the two to agree: a settled row
     * without a completion time cannot be written at all. Keeping them in one method
     * means no caller can get halfway.
     */
    public void succeed(String providerPaymentId, Instant when) {
        requirePending();
        this.status = PaymentStatus.SUCCEEDED;
        this.providerPaymentId = providerPaymentId;
        this.completedAt = when;
    }

    public void fail(String reason, Instant when) {
        requirePending();
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = when;
    }

    /**
     * Guards the one-way trip out of {@code PENDING}.
     *
     * <p>A gateway that delivers the same webhook twice -- which they all do -- must
     * not be able to re-settle a row and credit the invoice a second time. The service
     * checks the status before calling this; the exception is the backstop.
     */
    private void requirePending() {
        if (this.status.isSettled()) {
            throw new IllegalStateException(
                    "Payment %d is already %s".formatted(this.id, this.status));
        }
    }
}
