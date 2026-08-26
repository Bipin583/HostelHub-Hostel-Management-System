package com.hostelops.dto.fee;

import com.hostelops.domain.PaymentStatus;
import java.time.Instant;

/**
 * One payment attempt as the client sees it.
 *
 * <p>{@code idempotencyKey} is deliberately <em>not</em> here. It is the client's own
 * value and echoing it back adds nothing, while including it in a warden-visible
 * ledger response would leak one student's request identifiers to staff who have no
 * use for them.
 *
 * <p>{@code failureReason} is the gateway's own text, which is safe to show: it is
 * written for cardholders ("insufficient funds", "card expired") and is the one piece
 * of provider detail a student actually needs.
 */
public record FeePaymentResponse(
        Long id,
        Long feeId,
        Long studentId,
        Long amountPaise,
        String currency,
        PaymentStatus status,
        String provider,
        String providerOrderId,
        String providerPaymentId,
        String failureReason,
        Instant createdAt,
        Instant completedAt) {
}
