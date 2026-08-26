package com.hostelops.payment;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A payment gateway that moves no money.
 *
 * <p>The default provider, and the reason this project can be cloned and run without a
 * merchant account. Every code path a real payment takes -- an order created before the
 * payer is redirected, a signed callback, a verified signature, an invoice credited
 * under a row lock -- runs identically here. The only thing that differs is who holds
 * the secret and whether a card is charged.
 *
 * <p>{@link #DEV_SECRET} is hard-coded and published in this file on purpose. It
 * protects nothing: an attacker who forges a mock callback has caused a fictional
 * payment against a fictional invoice in somebody's development database. What the
 * well-known value buys is a test that can produce an authentic callback without a
 * back door in the verification code -- {@code PaymentCallbackIT} signs with
 * {@link #signatureFor} and the adapter verifies it the same way it verifies
 * Razorpay's. A "skip verification when mocking" flag would have made the check
 * something that has never run.
 *
 * <p>Selecting this provider in production would be a serious mistake, so it says so on
 * startup. Refusing to start instead was tempting and is wrong: this adapter has no way
 * to know which environment it is in, and a bean that guesses would either be
 * bypassable or would break the CI run that legitimately uses it.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    /** Not a credential. See the class Javadoc. */
    public static final String DEV_SECRET = "mock-gateway-development-secret";

    public static final String NAME = "mock";

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    @Override
    public String name() {
        return NAME;
    }

    /**
     * A recognisable placeholder rather than null.
     *
     * <p>Null would exercise the frontend's "this gateway has no publishable key" branch,
     * which no real gateway takes, and would hide a bug where the key is required but
     * never sent. A visibly fake value keeps the shape of a real response.
     */
    @Override
    public String publicKey() {
        return "mock_public_key";
    }

    @Override
    public String createOrder(PaymentOrderRequest request) {
        // Random rather than derived from the receipt: uq_fee_payments_provider_ref is a
        // real unique index, and an order id that repeated for a retried receipt would
        // collide on it -- turning a development convenience into a 409 nobody can explain.
        String orderId = "mock_order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("Mock gateway created order {} for {} paise (receipt {})",
                orderId, request.amountPaise(), request.receipt());
        return orderId;
    }

    @Override
    public boolean verify(String providerOrderId, String providerPaymentId, String signature) {
        return Signatures.matches(DEV_SECRET, providerOrderId, providerPaymentId, signature);
    }

    @Override
    public void assertConfigured() {
        log.warn("The MOCK payment gateway is active: payments will be recorded but no money will "
                + "move. Set app.payments.provider to a real gateway before going live.");
    }

    /**
     * Produces the signature a mock callback must carry.
     *
     * <p>Deliberately absent from {@link PaymentGateway}. A real gateway must not be able
     * to sign on the payer's behalf from inside this application -- that is the whole
     * point of the secret living at the provider -- so the ability to sign is a property
     * of this one adapter, and code that reaches for it has to name the mock to get it.
     * That is exactly the visibility wanted: {@code PaymentCallbackIT} and the
     * development frontend name it; nothing in the service layer can.
     */
    public String signatureFor(String providerOrderId, String providerPaymentId) {
        return Signatures.sign(DEV_SECRET, providerOrderId, providerPaymentId);
    }
}
