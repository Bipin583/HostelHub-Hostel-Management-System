package com.hostelops.dto.fee;

/**
 * What the browser needs to hand over to the gateway's checkout.
 *
 * <p>{@code publicKey} is the gateway's publishable identifier -- Razorpay's
 * {@code keyId} -- and it is the only credential that ever leaves the server. The
 * secret signs and verifies; it stays here. The predecessor rendered both into a
 * template because the checkout snippet on the vendor's quickstart page did, which put
 * the signing secret in view-source on every payment page.
 *
 * <p>{@code alreadyInitiated} is true when the request replayed an existing attempt --
 * same {@code Idempotency-Key}, so the original row came back rather than a second
 * one. The client uses it to distinguish "resume this checkout" from "a new checkout
 * just opened"; the amounts and order id are identical either way, which is the
 * guarantee being reported.
 *
 * <p>There is no {@code checkoutUrl}. Gateways differ on whether checkout is a
 * redirect or an in-page widget, and inventing a URL for the ones that have no such
 * thing would push provider-shaped detail into the client. The client already knows
 * which provider it is talking to -- {@code provider} says so -- and holds the
 * matching integration.
 */
public record PaymentInitiationResponse(
        Long paymentId,
        Long feeId,
        Long amountPaise,
        String currency,
        String provider,
        String providerOrderId,
        String publicKey,
        boolean alreadyInitiated) {
}
