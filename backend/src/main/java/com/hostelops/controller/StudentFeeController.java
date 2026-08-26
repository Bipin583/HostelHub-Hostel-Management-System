package com.hostelops.controller;

import com.hostelops.dto.fee.FeeResponse;
import com.hostelops.service.FeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A student's own invoices.
 *
 * <p>The list route takes no id, like {@link StudentSelfController}: the student comes
 * from the JWT subject, so there is nothing in the URL to tamper with. The detail route
 * takes an invoice id because it has to, and the ownership test is a predicate in the
 * query rather than a check after the fetch -- so somebody else's invoice and a
 * nonexistent one are the same 404, and probing ids tells the prober nothing.
 *
 * <p>A list rather than a page. A student has one invoice per term, so the whole set is
 * a handful of rows, and paging something the client always wants in full would make it
 * ask twice.
 */
@RestController
@RequestMapping("/api/v1/student/fees")
@Tag(name = "My fees (student)")
public class StudentFeeController {

    private final FeeService feeService;

    public StudentFeeController(FeeService feeService) {
        this.feeService = feeService;
    }

    @GetMapping
    @Operation(summary = "My invoices, newest due date first")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found, possibly empty"),
            @ApiResponse(responseCode = "403",
                    description = "The authenticated account is not a student",
                    content = @Content)})
    public List<FeeResponse> mine() {
        return feeService.mine();
    }

    @GetMapping("/{feeId}")
    @Operation(summary = "One of my invoices")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404",
                    description = "No such invoice, or it is not mine -- deliberately the same answer",
                    content = @Content)})
    public FeeResponse get(@PathVariable Long feeId) {
        return feeService.ownFee(feeId);
    }
}
