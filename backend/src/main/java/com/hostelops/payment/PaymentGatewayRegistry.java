package com.hostelops.payment;

import com.hostelops.config.PaymentProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code app.payments.provider} to a {@link PaymentGateway}, once, at startup.
 *
 * <p>The resolution happens in the constructor so a bad provider name is a boot
 * failure. Resolving lazily on first use would move that failure to a student's first
 * payment -- a deploy that looks healthy for a week and then breaks the one endpoint
 * that involves money, at the worst possible moment, in front of the wrong audience.
 *
 * <p>Callbacks are dispatched by stored provider name rather than through the selected
 * gateway. A payment started under one provider must be verifiable after the
 * configuration has moved on: switching providers on Monday cannot invalidate Sunday's
 * in-flight checkouts, and the {@code fee_payments.provider} column exists precisely so
 * this lookup is possible.
 *
 * <p>This is the whole of the "support another gateway" story. A new adapter is a class
 * implementing {@link PaymentGateway} with {@code @Component} on it -- Spring collects
 * it, this registry indexes it by name, and nothing else in the codebase mentions any
 * provider. The predecessor named Razorpay in templates, views and settings, so the
 * same change was a search across the repository with no way to know it was complete.
 */
@Component
public class PaymentGatewayRegistry {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayRegistry.class);

    private final Map<String, PaymentGateway> byName;
    private final PaymentGateway selected;

    public PaymentGatewayRegistry(List<PaymentGateway> gateways, PaymentProperties properties) {
        Map<String, PaymentGateway> index = new LinkedHashMap<>();
        for (PaymentGateway gateway : gateways) {
            String key = normalise(gateway.name());
            PaymentGateway clash = index.putIfAbsent(key, gateway);
            if (clash != null) {
                // Two adapters answering to one name means a callback could be verified by
                // the wrong secret, which is a silent authorization failure. Refuse to start.
                throw new IllegalStateException(
                        "Two payment gateways both claim the name '%s': %s and %s".formatted(
                                key, clash.getClass().getName(), gateway.getClass().getName()));
            }
        }
        this.byName = Map.copyOf(index);

        String wanted = normalise(properties.provider());
        PaymentGateway gateway = byName.get(wanted);
        if (gateway == null) {
            throw new IllegalStateException(
                    "app.payments.provider is '%s' but no such gateway exists. Available: %s"
                            .formatted(wanted, byName.keySet()));
        }
        gateway.assertConfigured();
        this.selected = gateway;
        log.info("Payment gateway '{}' selected ({} adapter(s) registered)", wanted, byName.size());
    }

    /** The gateway new payments are started with. */
    public PaymentGateway selected() {
        return selected;
    }

    /**
     * The gateway that handled an existing payment.
     *
     * @param provider the value stored on the {@code fee_payments} row
     * @throws PaymentGatewayException if no adapter answers to that name -- an adapter
     *                                 was removed while its payments were still settling,
     *                                 which is a deployment mistake and not something the
     *                                 caller can retry into success
     */
    public PaymentGateway forProvider(String provider) {
        PaymentGateway gateway = byName.get(normalise(provider));
        if (gateway == null) {
            throw new PaymentGatewayException(
                    "No adapter is registered for provider '" + provider + "'");
        }
        return gateway;
    }

    /**
     * {@code Locale.ROOT}, not the default locale. Lower-casing "I" under a Turkish
     * locale produces a dotless i, so a provider named with an I would stop resolving on
     * a machine configured in Turkey -- the canonical example of why case folding needs
     * an explicit locale.
     */
    private static String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
