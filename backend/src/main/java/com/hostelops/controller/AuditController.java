package com.hostelops.controller;

import com.hostelops.dto.audit.AuditActivityCountResponse;
import com.hostelops.dto.audit.AuditEventResponse;
import com.hostelops.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads the audit trail. Administrators only.
 *
 * <p>Under {@code /admin} rather than {@code /warden}, and that placement is the
 * authorization decision. {@link AuditService} applies no {@code AccessScope} -- a trail
 * narrowed to the reader's own hostel could not answer "did a warden touch a record outside
 * their scope", which is most of what it exists for. Since the service returns everything,
 * the route has to admit only people entitled to see everything, and
 * {@code /api/v1/admin/**} is where {@code SecurityConfig} requires ADMIN.
 *
 * <p>Read-only, with no write route of any kind. Rows appear here as a side effect of the
 * operations they describe, written by {@code AuditAspect} inside the caller's transaction;
 * an endpoint that could append to the trail would be an endpoint that could append a lie to
 * it, and the table's whole value is that nothing chose to write it.
 *
 * <p>Three ways in, because there are three questions people actually bring to an audit log:
 * what just happened, what happened to this record, and what has this person been doing.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
@Tag(name = "Audit trail (admin)")
public class AuditController {

    /**
     * How far back the activity counter looks when the caller says nothing.
     *
     * <p>Twenty-four hours, because the question the tile answers is "has anything happened
     * since I last looked", and a day is the interval an administrator actually checks on. A
     * fixed default rather than "since midnight": midnight in which zone would be the next
     * question, and the counter is a rate, not a calendar figure.
     */
    private static final Duration DEFAULT_ACTIVITY_WINDOW = Duration.ofDays(1);

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @Operation(summary = "Everything, newest first",
            description = "The activity feed. This is also the only view that shows the schedulers' work, "
                    + "because a job's events have no actor and so cannot appear under any actor id.")
    public Page<AuditEventResponse> recent(@PageableDefault(size = 20) Pageable pageable) {
        return auditService.recent(pageable);
    }

    /**
     * One record's history.
     *
     * <p>{@code entityType} is a free-string path variable rather than an enum. The table is
     * append-only, so it holds rows written by builds this one no longer matches -- and
     * binding to today's {@code AuditEntity} constants would reject the query with a 400
     * precisely when somebody is looking for the history of something that has since been
     * renamed or removed. The service upper-cases the value and an unrecognised type comes
     * back as an empty page, which is the honest answer: no rows match.
     */
    @GetMapping("/entity/{entityType}/{entityId}")
    @Operation(summary = "Every recorded change to one record, newest first",
            description = "Case-insensitive on the type. An unknown type is an empty page rather than an "
                    + "error, because a type this build does not define may still name rows in the table.")
    public Page<AuditEventResponse> forEntity(
            @Parameter(description = "The kind of record, e.g. ALLOCATION or FEE. Case-insensitive.")
            @PathVariable String entityType,
            @PathVariable Long entityId,
            @PageableDefault(size = 20) Pageable pageable) {
        return auditService.forEntity(entityType, entityId, pageable);
    }

    @GetMapping("/actor/{actorId}")
    @Operation(summary = "One person's activity, newest first",
            description = "Cannot show scheduled work: the jobs act with no actor by design, so an empty "
                    + "page here means this account did nothing, not that nothing happened.")
    public Page<AuditEventResponse> forActor(
            @PathVariable Long actorId,
            @PageableDefault(size = 20) Pageable pageable) {
        return auditService.forActor(actorId, pageable);
    }

    /**
     * How much has happened lately.
     *
     * <p>The admin dashboard's one tile, and the reason it lives on this controller rather
     * than on a dashboard of its own: it is an audit read, this controller already owns
     * {@link AuditService}, and one controller keeps to one service everywhere else here. A
     * separate admin dashboard controller would exist to hold a single number and would have
     * to inject the same service to get it.
     *
     * <p>{@code since} is an instant rather than a date because the window is a duration
     * backwards from now, not a calendar day -- and an {@code Instant} says which moment it
     * means without a timezone argument attached. Values in the future are not rejected; they
     * return zero, which is the truthful answer and saves a validation rule whose only effect
     * would be to turn a harmless query into an error.
     */
    @GetMapping("/activity")
    @Operation(summary = "How many audited operations happened since an instant",
            description = "Counts every event, including the schedulers' actorless ones. Defaults to the "
                    + "last 24 hours.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Counted"),
            @ApiResponse(responseCode = "400",
                    description = "MALFORMED_REQUEST when since is not an ISO-8601 instant",
                    content = @Content)})
    public AuditActivityCountResponse activity(
            @Parameter(description = "Count events after this instant, ISO-8601 like 2026-08-24T09:30:00Z; "
                    + "defaults to 24 hours ago")
            @RequestParam(required = false) Instant since) {
        Instant after = since != null ? since : Instant.now().minus(DEFAULT_ACTIVITY_WINDOW);
        return new AuditActivityCountResponse(after, auditService.countSince(after));
    }
}
