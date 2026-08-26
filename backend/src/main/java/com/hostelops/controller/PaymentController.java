package com.hostelops.controller;

import com.hostelops.dto.fee.FeePaymentResponse;
import com.hostelops.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payment ledger, as the office reads it.
 *
 * <p>Read-only, and that is the whole design. Every write in the payment story is
 * either a student starting an attempt or a provider callback being verified, both on
 * {@link StudentPaymentController}. A staff endpoint that could record a payment would
 * be an endpoint that credits an invoice without money moving -- and since the audit
 * trail cannot tell a mistaken entry from a fraudulent one, the safe number of such
 * endpoints is none.
 *
 * <p>Failed and pending attempts are listed alongside successful ones. A ledger that
 * showed only what succeeded would be unable to answer the question staff actually
 * ask, which is why a student says they paid and the invoice says otherwise.
 */
@RestController
@RequestMapping("/api/v1/warden/payments")
@Tag(name = "Payment ledger (warden)")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    @Operation(summary = "Every payment attempt by a student in the caller's scope, newest first",
            description = "Includes PENDING and FAILED attempts, which is what makes this useful for "
                    + "reconciling a provider's settlement report against this database.")
    public Page<FeePaymentResponse> ledger(@PageableDefault(size = 20) Pageable pageable) {
        return paymentService.ledger(pageable);
    }

    /**
     * Every attempt against one invoice.
     *
     * <p>Under {@code /fee/{feeId}} rather than on {@link FeeController} so that one
     * controller keeps to one service, as every controller here does. The invoice is
     * resolved through a scoped finder before any payment is read, so naming another
     * hostel's fee id does not disclose its payment history.
     */
    @GetMapping("/fee/{feeId}")
    @Operation(summary = "The payment history of one invoice, newest first")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found, possibly empty"),
            @ApiResponse(responseCode = "404",
                    description = "No such invoice, or its student is in another hostel",
                    content = @Content)})
    public List<FeePaymentResponse> forFee(@PathVariable Long feeId) {
        return paymentService.forFee(feeId);
    }
}
