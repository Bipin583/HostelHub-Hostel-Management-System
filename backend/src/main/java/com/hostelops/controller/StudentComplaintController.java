package com.hostelops.controller;

import com.hostelops.dto.complaint.ComplaintCreateRequest;
import com.hostelops.dto.complaint.ComplaintResponse;
import com.hostelops.service.ComplaintService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Raising and following a complaint, from the resident's side.
 *
 * <p>A student may create one, list their own, and read one of their own -- and that is
 * the whole surface. There is no status write here: moving a complaint forward is staff
 * work on {@link ComplaintController}, so the person who filed it cannot mark it resolved
 * to make their own number look better, and the person it is about cannot close it to make
 * it disappear.
 *
 * <p>Neither the list nor the create route carries a student id. The author is the JWT
 * subject in both cases, so there is nothing in the URL or the body to forge; the detail
 * route takes an id and pairs it with the subject in the query, so another student's
 * complaint and a nonexistent one are the same 404.
 */
@RestController
@RequestMapping("/api/v1/student/complaints")
@Tag(name = "My complaints (student)")
public class StudentComplaintController {

    private final ComplaintService complaintService;

    public StudentComplaintController(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    /**
     * Files a complaint in the caller's name.
     *
     * <p>The request has no {@code studentId} and no {@code status}: the author is the
     * authenticated caller and a complaint is always born {@code OPEN}. Both omissions are
     * the check -- a field that is not accepted cannot be forged.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Raise a complaint",
            description = "Filed against my own name as OPEN. The category and urgency are mine to set; "
                    + "the author, the status, and the timestamps are the server's.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Filed"),
            @ApiResponse(responseCode = "400",
                    description = "VALIDATION_FAILED for a blank title or description, or an unknown "
                            + "category or urgency",
                    content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "The authenticated account is not a student",
                    content = @Content)})
    public ComplaintResponse raise(@Valid @RequestBody ComplaintCreateRequest request) {
        return complaintService.raise(request);
    }

    @GetMapping
    @Operation(summary = "My complaints, newest first",
            description = "Every complaint I have filed, in any status, so the portal can show the ones "
                    + "still being worked alongside the ones already resolved.")
    public Page<ComplaintResponse> mine(@PageableDefault(size = 20) Pageable pageable) {
        return complaintService.mine(pageable);
    }

    @GetMapping("/{complaintId}")
    @Operation(summary = "One of my complaints, with its resolution note if it has one")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404",
                    description = "No such complaint, or it is not mine -- deliberately the same answer",
                    content = @Content)})
    public ComplaintResponse get(@PathVariable Long complaintId) {
        return complaintService.ownComplaint(complaintId);
    }
}
