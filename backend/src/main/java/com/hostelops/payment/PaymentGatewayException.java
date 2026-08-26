package com.hostelops.payment;

/**
 * A payment gateway could not be reached, or answered with something unusable.
 *
 * <p>Deliberately not an {@code ApiException}. An adapter's job is to talk to a
 * provider, and it should not be choosing HTTP status codes -- {@code PaymentService}
 * catches this and maps it to {@code PAYMENT_GATEWAY_ERROR}, which is the one place
 * that decides how a gateway outage looks from outside. Keeping the two apart is what
 * lets a second caller of an adapter (a reconciliation job, say) handle the failure
 * differently without inheriting a 502.
 *
 * <p>The message is for our logs, not for a student. It may contain the provider's
 * response body, which is why nothing routes it to a client.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
