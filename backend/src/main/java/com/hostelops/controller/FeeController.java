package com.hostelops.controller;

import com.hostelops.domain.FeeStatus;
import com.hostelops.dto.fee.FeeCollectionResponse;
import com.hostelops.dto.fee.FeeCreateRequest;
import com.hostelops.dto.fee.FeeResponse;
import com.hostelops.service.FeeService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invoices, as the hostel office manages them.
 *
 * <p>Billing is here and paying is not. A warden raises and writes off invoices;
 * money arrives through {@link StudentPaymentController}, where the payer is the one
 * authenticated. There is deliberately no endpoint on this controller that marks a
 * fee paid: the only thing that credits an invoice is a verified gateway callback, so
 * "paid" is never a field somebody can set.
 *
 * <p>Cancelling is a POST to a named sub-resource rather than a PATCH on
 * {@code status}, for the reason {@link ApplicationController} gives -- and with one
 * more here. A writable status would have to accept {@code PAID}, and accepting it
 * would mean an endpoint that books revenue nobody received.
 */
@RestController
@RequestMapping("/api/v1/warden/fees")
@Tag(name = "Fees (warden)")
public class FeeController {

    private final FeeService feeService;

    public FeeController(FeeService feeService) {
        this.feeService = feeService;
    }

    @GetMapping
    @Operation(summary = "Invoices for the caller's students, soonest due first",
            description = "Without a status filter this is every invoice, cancelled ones included. "
                    + "A warden sees only the gender their hostel houses; an admin sees everyone.")
    public Page<FeeResponse> list(
            @Parameter(description = "Narrow to one lifecycle state")
            @RequestParam(required = false) FeeStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return feeService.list(status, pageable);
    }

    /**
     * The collections figures.
     *
     * <p>Declared alongside {@code /{feeId}} and reached without ambiguity: Spring
     * prefers a literal segment to a template, so {@code /collections} never arrives
     * here as an invoice id. {@link RoomController} relies on the same rule for
     * {@code /occupancy}.
     */
    @GetMapping("/collections")
    @Operation(summary = "Billed against collected, one row per term",
            description = "Aggregated in the database and totalled server-side, so the headline figure "
                    + "and the per-term table cannot disagree.")
    public FeeCollectionResponse collections() {
        return feeService.collections();
    }

    @GetMapping("/{feeId}")
    @Operation(summary = "One invoice")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404",
                    description = "No such invoice, or its student is in another hostel -- both 404, for "
                            + "the reason StudentController.get explains",
                    content = @Content)})
    public FeeResponse get(@PathVariable Long feeId) {
        return feeService.get(feeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Raise an invoice against a student",
            description = "The invoice is born UNPAID; status is not an input, because only the payment "
                    + "arithmetic is allowed to move it.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Raised"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED with the offending fields",
                    content = @Content),
            @ApiResponse(responseCode = "409",
                    description = "DUPLICATE_RESOURCE when this student is already billed for that term "
                            + "(uq_hostel_fees_term) -- the guard against a double invoice is the "
                            + "constraint, not a prior read",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "The student is not in the caller's scope",
                    content = @Content)})
    public FeeResponse create(@Valid @RequestBody FeeCreateRequest request) {
        return feeService.create(request);
    }

    @PostMapping("/{feeId}/cancel")
    @Operation(summary = "Write an invoice off",
            description = "Part payments are left on the row rather than zeroed: a cancelled invoice with "
                    + "money against it still has to explain where that money went.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled"),
            @ApiResponse(responseCode = "409",
                    description = "FEE_ALREADY_SETTLED when the invoice is fully paid -- there is nothing "
                            + "left to forgive -- or already cancelled",
                    content = @Content)})
    public FeeResponse cancel(@PathVariable Long feeId) {
        return feeService.cancel(feeId);
    }
}
