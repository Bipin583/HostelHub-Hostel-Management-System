package com.hostelops.controller;

import com.hostelops.domain.ComplaintStatus;
import com.hostelops.dto.complaint.ComplaintAnalyticsResponse;
import com.hostelops.dto.complaint.ComplaintResponse;
import com.hostelops.dto.complaint.ComplaintStatusUpdateRequest;
import com.hostelops.service.ComplaintService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The maintenance queue, as staff work it.
 *
 * <p>Wardens do not raise complaints and students do not resolve them, so the two sides
 * are two controllers: {@link StudentComplaintController} owns the one write a resident
 * has, and this one owns the only write staff have, which is moving a complaint forward.
 * Nothing here can edit a complaint's text -- the description is the resident's account of
 * what happened, and an endpoint that let the person being complained about rewrite it
 * would make the whole table unusable as evidence.
 *
 * <p>Status is the only mutable field, and it moves in one direction. The legality of a
 * move belongs to {@code ComplaintStatus.canTransitionTo} rather than to this layer, so
 * the refusal names both states: a 409 reading "a complaint that is RESOLVED cannot become
 * OPEN" tells a client what to do instead, which a bare 400 does not.
 */
@RestController
@RequestMapping("/api/v1/warden/complaints")
@Tag(name = "Complaints (warden)")
public class ComplaintController {

    /**
     * The default analytics window.
     *
     * <p>Ninety days is roughly a term, which is the period staff actually compare against.
     * The service caps the parameter at a year; the cap is its rule rather than a
     * {@code @Max} here, because the reason for it -- that "all time" is a number which
     * stops changing and stops being worth querying -- is a fact about the data, not about
     * this endpoint.
     */
    private static final String DEFAULT_ANALYTICS_WINDOW_DAYS = "90";

    private final ComplaintService complaintService;

    public ComplaintController(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    @GetMapping
    @Operation(summary = "The complaint queue for the caller's hostels, oldest first",
            description = "Oldest first, unlike every other list here: a queue is worked from the front, "
                    + "and the row most worth seeing is the one that has been waiting longest.")
    public Page<ComplaintResponse> queue(
            @Parameter(description = "Restrict to one status; omit for the whole queue")
            @RequestParam(required = false) ComplaintStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return complaintService.queue(status, pageable);
    }

    /**
     * Resolution timings and backlog for the caller's hostels.
     *
     * <p>Sits above {@code /{complaintId}} in this file and resolves safely regardless:
     * Spring prefers a literal segment to a template, the same property
     * {@code RoomController}'s {@code /occupancy} relies on. Were it otherwise, this path
     * would be a request for the complaint whose id is "analytics" and a 400.
     *
     * <p>The window narrows the timings only. Backlog is a present-tense fact, so the
     * oldest untouched complaint stays visible however short a window the client asks for
     * -- which is the whole point of surfacing it.
     */
    @GetMapping("/analytics")
    @Operation(summary = "How long complaints are taking, and what is stuck",
            description = "Three aggregate queries, none of which load complaint rows. Times are split "
                    + "into the queue wait and the work itself, because those two numbers have "
                    + "different fixes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Computed; zero-valued when the window is empty"),
            @ApiResponse(responseCode = "400",
                    description = "BAD_REQUEST when windowDays is below 1 or above 366",
                    content = @Content)})
    public ComplaintAnalyticsResponse analytics(
            @Parameter(description = "How many days back the resolution timings look, 1 to 366")
            @RequestParam(defaultValue = DEFAULT_ANALYTICS_WINDOW_DAYS) int windowDays) {
        return complaintService.analytics(windowDays);
    }

    @GetMapping("/{complaintId}")
    @Operation(summary = "One complaint in full, with its status history timestamps")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404",
                    description = "No such complaint, or its author is in another hostel",
                    content = @Content)})
    public ComplaintResponse get(@PathVariable Long complaintId) {
        return complaintService.get(complaintId);
    }

    /**
     * Moves a complaint forward.
     *
     * <p>A POST to a sub-resource rather than a {@code PATCH} of the complaint, because
     * this is not a field assignment: it is a transition with rules, and the two failure
     * modes it has -- an illegal move, a resolution with nothing said about it -- are
     * answers to "may this happen", not to "is this body well-formed". Naming the action in
     * the path keeps that distinction visible in the log.
     *
     * <p>Resolving requires a note. The rule is the service's rather than a bean-validation
     * constraint because it is conditional on {@code status}, and the 400 it produces is
     * {@code RESOLUTION_NOTE_REQUIRED} rather than a generic validation failure so a client
     * can prompt for exactly the missing thing.
     */
    @PostMapping("/{complaintId}/status")
    @Operation(summary = "Advance a complaint to IN_PROGRESS or RESOLVED",
            description = "Forward only, and never out of RESOLVED -- reopening is a new complaint, which "
                    + "is what keeps the original's resolution timings honest.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Moved"),
            @ApiResponse(responseCode = "400",
                    description = "RESOLUTION_NOTE_REQUIRED when resolving without saying what was done",
                    content = @Content),
            @ApiResponse(responseCode = "404",
                    description = "No such complaint, or its author is in another hostel",
                    content = @Content),
            @ApiResponse(responseCode = "409",
                    description = "ILLEGAL_STATE_TRANSITION, naming both the current and requested status",
                    content = @Content)})
    public ComplaintResponse updateStatus(
            @PathVariable Long complaintId,
            @Valid @RequestBody ComplaintStatusUpdateRequest request) {
        return complaintService.updateStatus(complaintId, request);
    }
}
