package com.hostelops.controller;

import com.hostelops.dto.fee.FeePaymentResponse;
import com.hostelops.dto.fee.PaymentCallbackRequest;
import com.hostelops.dto.fee.PaymentInitiationRequest;
import com.hostelops.dto.fee.PaymentInitiationResponse;
import com.hostelops.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paying an invoice, from the student's side.
 *
 * <p>Two writes, and they are two endpoints for a reason worth stating. {@code initiate}
 * opens an attempt and asks the gateway for an order; {@code confirm} takes the gateway's
 * signed answer and credits the invoice. They are separated by the payer leaving for the
 * provider's checkout and coming back, so nothing about the first request can be trusted
 * to still hold at the second -- the balance may have moved, the invoice may have been
 * cancelled, another attempt may have settled it. The service re-derives everything at
 * confirm time; this controller's job is only to route the two halves and carry the two
 * things the URL cannot: the idempotency key and the signed callback body.
 *
 * <h2>The key is a header, and it is required</h2>
 *
 * <p>{@code Idempotency-Key} rides in a header rather than the body because it is
 * metadata about the request, not about the payment -- the same reasoning HTTP's own
 * conditional headers follow. It is bound here with {@code required = true}, so a client
 * that omits it is a 400 from the framework naming the header, before any service code
 * runs. Generating one server-side when it is missing would be the tempting convenience
 * that quietly defeats the guarantee: every retry would carry a fresh key and charge
 * again. The header is already in {@code SecurityConfig}'s CORS allow-list, so a browser
 * client can actually send it cross-origin.
 *
 * <h2>Confirm is a POST with a body, never a GET</h2>
 *
 * <p>The predecessor settled payments with a GET whose query string it trusted, which
 * meant a link in a browser's history could book a fee as paid. Here the callback is a
 * POST carrying {@link PaymentCallbackRequest}, so the references stay out of access logs
 * and history, and -- more to the point -- the amount and the outcome are not in it at
 * all. The amount comes from the row the order id names; the outcome comes from the
 * adapter verifying the signature. A caller cannot assert either.
 */
@RestController
@RequestMapping("/api/v1/student/payments")
@Tag(name = "My payments (student)")
public class StudentPaymentController {

    private final PaymentService paymentService;

    public StudentPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    @Operation(summary = "My payment attempts, newest first",
            description = "Every attempt I have made, including the ones still pending or failed, so the "
                    + "portal can show a checkout that was closed halfway.")
    public Page<FeePaymentResponse> mine(@PageableDefault(size = 20) Pageable pageable) {
        return paymentService.mine(pageable);
    }

    /**
     * Opens a payment attempt and returns what the client needs to start a checkout.
     *
     * <p>Not idempotent in the HTTP sense -- it creates -- but made safe to retry by the
     * key: a repeat with the same key gets the same attempt back rather than a second one,
     * and a repeat carrying a settled attempt's key is refused so a paid order cannot be
     * reopened. The response's {@code replay} flag says which happened.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start paying one of my invoices",
            description = "Claims the idempotency key, then asks the configured gateway for an order. "
                    + "Returns the order reference and the gateway's public key so the browser can open "
                    + "checkout. Nothing is charged yet.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Attempt opened (or the existing one replayed)"),
            @ApiResponse(responseCode = "400", description =
                    "BAD_REQUEST when the Idempotency-Key header is missing or over-long; "
                            + "PAYMENT_AMOUNT_INVALID when the amount exceeds what the invoice has "
                            + "outstanding",
                    content = @Content),
            @ApiResponse(responseCode = "409", description =
                    "DUPLICATE_RESOURCE when the key belongs to another request; "
                            + "PAYMENT_ALREADY_SETTLED when its attempt is already paid -- start a new "
                            + "payment with a new key; FEE_ALREADY_SETTLED when the invoice needs nothing",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "The invoice is not mine or does not exist",
                    content = @Content),
            @ApiResponse(responseCode = "502",
                    description = "PAYMENT_GATEWAY_ERROR when the provider could not be reached; nothing "
                            + "was charged and the same key may be retried",
                    content = @Content)})
    public PaymentInitiationResponse initiate(
            @Valid @RequestBody PaymentInitiationRequest request,
            @Parameter(description = "Client-chosen key that makes this request safe to retry", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return paymentService.initiate(request, idempotencyKey);
    }

    /**
     * Confirms a payment from the gateway's signed callback.
     *
     * <p>The three references are unpacked from the body and handed to the service as three
     * arguments -- the signature named as such -- rather than passed as the record. The
     * audit aspect redacts by parameter name, and a signature reaching {@code payload_diff}
     * through an innocuously named field is exactly the leak the naming avoids.
     */
    @PostMapping("/callback")
    @Operation(summary = "Confirm a payment the gateway has completed",
            description = "Verifies the signature, then credits the invoice under a row lock. The amount "
                    + "and the outcome come from the server, never from this request.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verified and credited"),
            @ApiResponse(responseCode = "400",
                    description = "PAYMENT_VERIFICATION_FAILED when the signature does not verify",
                    content = @Content),
            @ApiResponse(responseCode = "409", description =
                    "PAYMENT_ALREADY_SETTLED when this attempt was already confirmed; FEE_ALREADY_SETTLED "
                            + "when the invoice was settled by another attempt while this one was in flight "
                            + "-- the office is alerted to reconcile, because the money is captured",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "No attempt matches the order reference",
                    content = @Content)})
    public FeePaymentResponse confirm(@Valid @RequestBody PaymentCallbackRequest callback) {
        return paymentService.settle(
                callback.providerOrderId(), callback.providerPaymentId(), callback.signature());
    }
}
