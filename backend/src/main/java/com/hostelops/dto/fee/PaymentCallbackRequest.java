package com.hostelops.dto.fee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the gateway sends back once the payer is done.
 *
 * <p>Notice what is missing: an amount, and a status. Both are decided here, not by
 * the caller. The amount comes from the {@code fee_payments} row this callback names,
 * and the outcome comes from the adapter verifying {@code signature} against the
 * secret. A callback that could name its own amount, or assert its own success, is an
 * endpoint that lets anyone who learns an order id mark a fee paid -- which is how the
 * predecessor's "payment success" page worked, being a plain GET that trusted its
 * query string.
 *
 * <p>{@code signature} is required even for the mock provider. The mock computes a
 * real HMAC over the same fields with a fixed development secret, so the verification
 * path is the one under test in every environment; an adapter that skips the check
 * when convenient is an adapter whose check has never run.
 *
 * <p>This is bound from a POST body rather than query parameters so the references do
 * not land in access logs and browser history, and so a link cannot settle a payment.
 */
public record PaymentCallbackRequest(
        @NotBlank @Size(max = 120) String providerOrderId,
        @NotBlank @Size(max = 120) String providerPaymentId,
        @NotBlank @Size(max = 512) String signature) {
}
