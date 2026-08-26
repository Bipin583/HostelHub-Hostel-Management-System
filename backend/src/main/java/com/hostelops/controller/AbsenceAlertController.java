package com.hostelops.controller;

import com.hostelops.dto.attendance.AbsenceAlertResponse;
import com.hostelops.service.AbsenceAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The absence alerts a warden works through.
 *
 * <p>Nothing here raises an alert. Alerts are the nightly scan's output -- a derived
 * reading of the attendance register -- so the only way to create one is to have the
 * register say a student has been missing, and the only way to make one go away on its
 * merits is for the register to say they came back. A create endpoint would let staff
 * assert an absence the register does not support, and a delete endpoint would let them
 * remove one it does.
 *
 * <p>What is left is acknowledgement: recording that a human has seen the alert and is
 * dealing with it. That is the one fact the scan cannot derive, which is why it is the one
 * write on this controller. Triggering a scan manually is a different thing again and lives
 * on {@link AdminJobController}, because the scan is deliberately unscoped and this route
 * prefix admits wardens.
 */
@RestController
@RequestMapping("/api/v1/warden/absence-alerts")
@Tag(name = "Absence alerts (warden)")
public class AbsenceAlertController {

    private final AbsenceAlertService absenceAlertService;

    public AbsenceAlertController(AbsenceAlertService absenceAlertService) {
        this.absenceAlertService = absenceAlertService;
    }

    @GetMapping
    @Operation(summary = "Alerts still waiting on somebody, newest first",
            description = "The working list: unacknowledged alerts for students in the caller's hostels. "
                    + "Each row carries the current length of the absence as at today, recomputed on "
                    + "read rather than stored, so a list left open overnight cannot go stale.")
    public Page<AbsenceAlertResponse> openAlerts(@PageableDefault(size = 20) Pageable pageable) {
        return absenceAlertService.openAlerts(pageable);
    }

    /**
     * Every alert, acknowledged ones included.
     *
     * <p>A literal segment beside no template on this controller, so there is no ambiguity
     * to resolve -- but it is {@code /all} rather than a {@code ?includeAcknowledged=true}
     * flag on the list above for a plainer reason: the two lists answer different questions.
     * One is a queue, the other is a history, and collapsing them into one route with a
     * boolean makes the queue's default behaviour depend on a parameter a client can forget.
     */
    @GetMapping("/all")
    @Operation(summary = "Every alert in the caller's hostels, newest first",
            description = "Acknowledged and auto-closed alerts included. This is the history -- what the "
                    + "scan has found over time and what was done about it.")
    public Page<AbsenceAlertResponse> allAlerts(@PageableDefault(size = 20) Pageable pageable) {
        return absenceAlertService.allAlerts(pageable);
    }

    /**
     * Records that a member of staff has seen an alert.
     *
     * <p>A POST to a named sub-resource rather than a {@code PATCH} setting
     * {@code acknowledgedAt}: the timestamp and the acknowledging user are the server's to
     * write, and there is no version of this request that should be able to say when it
     * happened or on whose behalf.
     *
     * <p>Acknowledging twice is a 409, not a no-op. Two wardens both believing they handled
     * the same case is worth surfacing, and the row already names who acknowledged it first
     * -- so a silent overwrite would erase exactly the fact that makes the second call
     * interesting. An alert the scan auto-closed because the student returned hits the same
     * 409, and that reads correctly: there is nothing left to acknowledge.
     */
    @PostMapping("/{alertId}/acknowledge")
    @Operation(summary = "Acknowledge an alert",
            description = "Records the caller and the server's clock against the alert. Both are the "
                    + "server's to set; neither is a request field.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Acknowledged"),
            @ApiResponse(responseCode = "404",
                    description = "No such alert, or its student is in another hostel",
                    content = @Content),
            @ApiResponse(responseCode = "409",
                    description = "ALERT_ALREADY_ACKNOWLEDGED, whether by a colleague or by the scan "
                            + "closing it when the student returned",
                    content = @Content)})
    public AbsenceAlertResponse acknowledge(@PathVariable Long alertId) {
        return absenceAlertService.acknowledge(alertId);
    }
}
