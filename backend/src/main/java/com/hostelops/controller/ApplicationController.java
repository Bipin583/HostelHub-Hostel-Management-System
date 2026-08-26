package com.hostelops.controller;

import com.hostelops.domain.ApplicationStatus;
import com.hostelops.dto.application.ApplicationApprovalRequest;
import com.hostelops.dto.application.ApplicationRejectionRequest;
import com.hostelops.dto.application.ApplicationResponse;
import com.hostelops.service.ApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The warden's application queue.
 *
 * <p>Approve and reject are POSTs to named sub-resources rather than a PATCH that
 * sets {@code status}. The two are not interchangeable operations on a field:
 * approving allocates a room and can fail for reasons that have nothing to do with
 * the application, and rejecting requires a reason. A generic status write would
 * have to accept {@code PENDING} as an input and decide what that means.
 */
@RestController
@RequestMapping("/api/v1/warden/applications")
@Tag(name = "Applications (warden)")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    @Operation(summary = "Applications in the caller's hostel scope",
            description = "Without a status filter this is the full history, newest first. Filtered to "
                    + "PENDING it is the work queue, and comes back oldest first instead.")
    public Page<ApplicationResponse> list(
            @RequestParam(required = false) ApplicationStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return applicationService.list(status, pageable);
    }

    @GetMapping("/pending")
    @Operation(summary = "The pending queue, oldest first")
    public Page<ApplicationResponse> pending(@PageableDefault(size = 20) Pageable pageable) {
        return applicationService.listPending(pageable);
    }

    @GetMapping("/{applicationId}")
    @Operation(summary = "One application")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404",
                    description = "No such application, or the applicant is in another hostel",
                    content = @Content)})
    public ApplicationResponse get(@PathVariable Long applicationId) {
        return applicationService.get(applicationId);
    }

    @PostMapping("/{applicationId}/approve")
    @Operation(summary = "Approve an application and allocate a room",
            description = "The approval and the allocation are one transaction. If no eligible room has a "
                    + "free bed the approval is rolled back and the application stays pending, so there is "
                    + "no state in which a student is approved with nowhere to sleep.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approved and allocated"),
            @ApiResponse(responseCode = "409", description =
                    "NO_ROOM_AVAILABLE when nothing eligible has a free bed, in which case nothing was "
                            + "changed; ILLEGAL_STATE_TRANSITION when another warden already decided it",
                    content = @Content)})
    public ApplicationResponse approve(
            @PathVariable Long applicationId,
            @Valid @RequestBody(required = false) ApplicationApprovalRequest request) {
        return applicationService.approve(applicationId, request == null ? null : request.note());
    }

    @PostMapping("/{applicationId}/reject")
    @Operation(summary = "Reject an application with a reason",
            description = "Returns the student to NOT_APPLIED so they can apply again. The rejection and "
                    + "its reason stay on the application permanently.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rejected"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED when no reason was given",
                    content = @Content),
            @ApiResponse(responseCode = "409",
                    description = "ILLEGAL_STATE_TRANSITION when the application was already decided",
                    content = @Content)})
    public ApplicationResponse reject(
            @PathVariable Long applicationId,
            @Valid @RequestBody ApplicationRejectionRequest request) {
        return applicationService.reject(applicationId, request.reason());
    }
}
