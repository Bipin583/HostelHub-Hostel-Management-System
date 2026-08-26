package com.hostelops.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.hostelops.config.PaymentProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The Razorpay adapter.
 *
 * <p>Hand-rolled against the REST API rather than pulled in as the vendor SDK. The two
 * calls this application makes are one POST and one HMAC comparison; a dependency that
 * brings its own HTTP client, JSON library and release cadence to provide those is a
 * poor trade, and the SDK's exception types would leak into the signature of
 * {@link PaymentGateway} unless wrapped anyway -- which is most of the work.
 *
 * <p>This bean exists in every environment, including the ones with no Razorpay
 * account, and that is fine because {@link #assertConfigured()} is only called on the
 * selected provider. Selecting it without credentials therefore fails at startup, as
 * {@link PaymentProperties} promises, rather than at a student's first payment.
 *
 * <p>Nothing here logs {@code keySecret}, and the failure paths log the provider's
 * response body only at debug. A gateway error message can echo the request that caused
 * it, and this is the one request in the application that carries a signing secret in
 * its headers.
 */
@Component
public class RazorpayPaymentGateway implements PaymentGateway {

    public static final String NAME = "razorpay";

    /**
     * Not configurable, deliberately. A payment endpoint that can be repointed by an
     * environment variable is a payment endpoint that can be repointed by whoever can
     * set one. Tests inject a {@link RestClient.Builder} instead, which is a compile-time
     * seam rather than a runtime one.
     */
    private static final String API_BASE = "https://api.razorpay.com/v1";

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentGateway.class);

    private final PaymentProperties.Razorpay credentials;
    private final RestClient restClient;

    public RazorpayPaymentGateway(PaymentProperties properties, RestClient.Builder builder) {
        this.credentials = properties.razorpay();
        this.restClient = builder
                .baseUrl(API_BASE)
                // Basic auth with the key id as username is Razorpay's scheme. Set as a
                // default header so no call site can forget it and send an unauthenticated
                // order request, which the API answers with a 401 that looks like an outage.
                .defaultHeaders(headers -> headers.setBasicAuth(
                        credentials.keyId() == null ? "" : credentials.keyId(),
                        credentials.keySecret() == null ? "" : credentials.keySecret()))
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String publicKey() {
        return credentials.keyId();
    }

    @Override
    public void assertConfigured() {
        if (!credentials.isConfigured()) {
            throw new IllegalStateException(
                    "app.payments.provider is 'razorpay' but app.payments.razorpay.key-id / key-secret "
                            + "are not set. Set them, or select the mock provider.");
        }
    }

    @Override
    public String createOrder(PaymentOrderRequest request) {
        Map<String, Object> payload = Map.of(
                "amount", request.amountPaise(),
                "currency", request.currency(),
                "receipt", request.receipt(),
                // Auto-capture. The alternative is a two-step authorise-then-capture flow,
                // which is worth the complexity when goods are shipped later and can be
                // cancelled; a hostel fee is owed the moment it is billed, so holding an
                // authorisation only creates a second way for the money to go missing.
                "payment_capture", 1);

        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // Includes 4xx and 5xx from the provider as well as connect and read timeouts.
            // All of them mean the same thing to the caller: no order exists, nothing was
            // charged, and retrying the identical request is safe.
            throw new PaymentGatewayException("Razorpay order creation failed", e);
        }

        if (response == null || !response.hasNonNull("id")) {
            log.debug("Unexpected Razorpay order response: {}", response);
            throw new PaymentGatewayException("Razorpay accepted the order but returned no order id");
        }
        return response.get("id").asText();
    }

    @Override
    public boolean verify(String providerOrderId, String providerPaymentId, String signature) {
        // Signed with the API secret, which is also the webhook secret for the client-side
        // handler's payload. Razorpay's payload is exactly orderId|paymentId, which is why
        // Signatures uses that format for the mock as well.
        return Signatures.matches(credentials.keySecret(), providerOrderId, providerPaymentId, signature);
    }
}
