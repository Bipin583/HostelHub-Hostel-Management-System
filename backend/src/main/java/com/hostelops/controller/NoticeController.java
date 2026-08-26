package com.hostelops.controller;

import com.hostelops.dto.notice.NoticeCreateRequest;
import com.hostelops.dto.notice.NoticeResponse;
import com.hostelops.service.NoticeService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The notice board, from the posting side.
 *
 * <p>Post, list, take down. There is deliberately no edit route: a notice is a thing
 * people have already read and acted on, so changing its text after the fact would leave
 * two versions of the truth in circulation with only the second one recoverable. Getting
 * one wrong is a delete and a repost, which the audit trail records as two acts -- and
 * that is the accurate description of what happened.
 *
 * <p>What "my hostels" means is the service's decision, not this layer's. A warden's post
 * has its audience narrowed to their own scope by {@code NoticeService} regardless of what
 * the body asked for, which is why {@link NoticeCreateRequest}'s audience fields being
 * nullable is safe: null means "everyone" only for an admin.
 */
@RestController
@RequestMapping("/api/v1/warden/notices")
@Tag(name = "Notice board (warden)")
public class NoticeController {

    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    /**
     * Posts a notice.
     *
     * <p>Naming another hostel's audience is a 403 rather than a silent narrowing. A warden
     * who aims a notice at a building they do not run has misunderstood something, and
     * quietly redirecting it would leave them believing it went where they aimed.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Post a notice",
            description = "The author is the caller and the publication time is the server's; neither is "
                    + "a request field. A warden's audience is narrowed to their own hostel whatever "
                    + "the body says.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Posted"),
            @ApiResponse(responseCode = "400",
                    description = "VALIDATION_FAILED for a blank title or body, a year outside 1-5, or an "
                            + "expiry that has already passed",
                    content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "OUT_OF_SCOPE when a warden names a hostel type they do not run",
                    content = @Content)})
    public NoticeResponse post(@Valid @RequestBody NoticeCreateRequest request) {
        return noticeService.post(request);
    }

    @GetMapping
    @Operation(summary = "Everything I may administer, newest first",
            description = "Wider than the student feed on purpose: expired notices are included, because "
                    + "this is the answer to \"what did we post last term\".")
    public Page<NoticeResponse> manageable(@PageableDefault(size = 20) Pageable pageable) {
        return noticeService.manageable(pageable);
    }

    /**
     * Takes a notice down.
     *
     * <p>A hard delete. Expiry is already a first-class thing this table expresses, so
     * soft-deleting by setting {@code expiresAt} would conflate "this stopped being current"
     * with "this should never have been posted" -- and the second case is exactly the one
     * where leaving the row readable defeats the point. The audit event survives the row.
     *
     * <p>204 with no body: there is nothing meaningful to return about a thing that no
     * longer exists, and returning the deleted notice would invite a client to render it.
     */
    @DeleteMapping("/{noticeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Take a notice down",
            description = "By its author, or by an administrator. Being able to see a notice is not being "
                    + "able to remove it -- a warden reads the campus-wide posts and cannot delete them.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "403",
                    description = "FORBIDDEN when the caller is neither the author nor an administrator",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "No such notice", content = @Content)})
    public void delete(@PathVariable Long noticeId) {
        noticeService.delete(noticeId);
    }
}
