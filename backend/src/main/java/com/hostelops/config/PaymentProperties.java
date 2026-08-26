package com.hostelops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Payment gateway selection.
 *
 * <p>{@code provider} names which {@code PaymentGateway} implementation handles
 * checkout. Nothing outside the adapters reads it: services depend on the
 * interface, and {@code PaymentGatewayRegistry} resolves the name once at startup.
 * That is the whole point of keeping it here -- the predecessor referred to
 * Razorpay by name in views, templates and settings alike, so "support another
 * provider" meant an audit of the codebase rather than a configuration change.
 *
 * <p>The Razorpay credentials default to blank. A blank secret is only a problem
 * if the Razorpay adapter is the selected provider, and that adapter refuses to
 * start without one -- so a misconfiguration fails at boot rather than at a
 * student's first payment.
 */
@ConfigurationProperties(prefix = "app.payments")
public record PaymentProperties(String provider, Razorpay razorpay) {

    public PaymentProperties {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("app.payments.provider must name a payment gateway");
        }
        provider = provider.trim().toLowerCase();
        razorpay = razorpay == null ? new Razorpay("", "") : razorpay;
    }

    public record Razorpay(String keyId, String keySecret) {

        public boolean isConfigured() {
            return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
        }
    }
}
