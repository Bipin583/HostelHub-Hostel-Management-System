package com.hostelops.payment;

/**
 * One payment provider, behind an interface narrow enough that swapping providers is a
 * configuration change.
 *
 * <p>Two methods do the work, and the split between them is the security boundary.
 * {@link #createOrder} is the only call that spends anything; {@link #verify} is the
 * only thing that decides a payment succeeded. Nothing outside this package knows what
 * a Razorpay order looks like, and no service asks a gateway "did this work?" in any
 * other way.
 *
 * <p>Implementations must be stateless and thread-safe: one instance serves every
 * concurrent payment. That is easy to honour and worth stating, because the obvious
 * shortcut -- caching the last order id in a field to save a parameter -- would cross
 * two students' payments under load.
 *
 * <p>Adapters throw {@link PaymentGatewayException} for transport and protocol
 * failures. They never throw for "the payer's card was declined": that is a settled
 * outcome, reported through {@link #verify} returning false, and it belongs in the
 * ledger rather than in an exception handler.
 */
public interface PaymentGateway {

    /**
     * The name this gateway answers to in {@code app.payments.provider}, and the value
     * stored in {@code fee_payments.provider}.
     *
     * <p>One string for both, so a historical payment row names the adapter that can
     * still verify it. Two separate names -- a bean name and a stored label -- is how a
     * provider migration ends up with rows nothing can reconcile.
     *
     * <p>Lower case by convention; {@code PaymentGatewayRegistry} lowercases both sides
     * before matching so a capitalised environment variable is not a boot failure.
     */
    String name();

    /**
     * The publishable key the browser needs, or null if this gateway has no such
     * concept.
     *
     * <p>Named {@code publicKey} rather than {@code apiKey} so that anything sending a
     * secret from here reads wrong on the line it is written.
     */
    String publicKey();

    /**
     * Opens an order at the provider and returns its reference.
     *
     * @return the provider's order id, stored in {@code fee_payments.provider_order_id}
     * @throws PaymentGatewayException if the provider is unreachable or its answer
     *                                 cannot be understood
     */
    String createOrder(PaymentOrderRequest request);

    /**
     * Whether a callback genuinely came from the provider.
     *
     * <p>This is the entire authorization for crediting money to an invoice. A callback
     * arrives over the public internet, from a client that would like the payment to
     * have succeeded, so the signature is the only reason to believe any of it. An
     * adapter that returns true without checking would turn the callback endpoint into
     * a free-money button, which is why the mock adapter checks a real HMAC too.
     */
    boolean verify(String providerOrderId, String providerPaymentId, String signature);

    /**
     * Throws if this gateway is selected but not usable -- missing credentials, most
     * likely.
     *
     * <p>Called once by {@link PaymentGatewayRegistry} at startup, and only on the
     * selected gateway. That is why it is a method rather than a constructor check:
     * every adapter is a bean in every environment, and a Razorpay adapter that threw
     * on construction would stop the application from booting with the mock provider
     * selected and no Razorpay account in sight.
     */
    default void assertConfigured() {
    }
}
