package com.hostelops.dto.fee;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * A student starting a payment.
 *
 * <p>{@code amountPaise} is supplied rather than inferred from the invoice, because
 * part payments are a real requirement -- a student paying half now and half after
 * their scholarship clears. Inferring the full outstanding balance would make that
 * impossible, and the service still checks the amount against what is actually owed,
 * so naming it cannot be used to overpay.
 *
 * <p>The idempotency key is not in this body. It travels as the
 * {@code Idempotency-Key} header, which is where HTTP conventions put it and, more
 * usefully, keeps it out of the payload a client might otherwise regenerate when it
 * rebuilds the request object on retry -- the exact moment the key has to stay the
 * same.
 */
public record PaymentInitiationRequest(
        @NotNull Long feeId,
        @NotNull @Positive Long amountPaise) {
}
