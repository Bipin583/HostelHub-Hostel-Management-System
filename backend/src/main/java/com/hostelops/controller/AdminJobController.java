package com.hostelops.controller;

import com.hostelops.dto.attendance.AbsenceScanResultResponse;
import com.hostelops.dto.fee.FeeReminderDayCountResponse;
import com.hostelops.dto.fee.FeeReminderRunResponse;
import com.hostelops.service.AbsenceAlertService;
import com.hostelops.service.FeeReminderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual triggers for the scheduled jobs. Administrators only.
 *
 * <p>Under {@code /admin} for a specific reason, and it is not seniority. Both services
 * these routes call are deliberately unscoped: {@code FeeReminderService.run} reminds every
 * overdue student on campus and {@code AbsenceAlertService.scan} reads the whole register,
 * because a job has no hostel of its own. {@code /api/v1/warden/**} admits wardens, so
 * putting either here would hand a warden a lever that reaches into hostels they cannot
 * otherwise see -- and the audit event would name them as the actor for work spanning the
 * entire campus. The route prefix is the only thing preventing that, since the services
 * themselves apply no {@code AccessScope}.
 *
 * <p>These exist because a cron that only fires at 03:00 is untestable in a demo and
 * unrecoverable in an incident. If the scheduler was disabled overnight, or a run failed and
 * was logged, somebody has to be able to say "do it now" -- and doing it by hand against the
 * database is exactly the intervention the audit trail cannot describe.
 *
 * <h2>Why the date defaults to today in UTC</h2>
 *
 * <p>Both routes default their date the same way the jobs do: {@code LocalDate.now(UTC)},
 * matching the {@code zone = "UTC"} on the two crons. That agreement is the point. If this
 * controller read the server's local date while the cron fired on a UTC one, a manual run
 * and a scheduled run could disagree about which day they were acting for -- and for the
 * reminder job that disagreement is not cosmetic: {@code uq_fee_reminders_per_day} keys on
 * the date, so a run under the wrong day would send a second reminder to somebody who
 * already had one. The date stays overridable because reconstructing a day that was missed
 * is the whole reason an operator reaches for these routes.
 */
@RestController
@RequestMapping("/api/v1/admin/jobs")
@Tag(name = "Scheduled jobs (admin)")
public class AdminJobController {

    private final FeeReminderService feeReminderService;
    private final AbsenceAlertService absenceAlertService;

    public AdminJobController(
            FeeReminderService feeReminderService, AbsenceAlertService absenceAlertService) {
        this.feeReminderService = feeReminderService;
        this.absenceAlertService = absenceAlertService;
    }

    /**
     * Runs the fee-reminder job now.
     *
     * <p>Safe to call twice. The second run reports every invoice as skipped rather than
     * sent, because {@code uq_fee_reminders_per_day} refuses the duplicate at the database
     * -- which is what makes this route safe to expose at all, and what
     * {@code FeeReminderIdempotencyIT} asserts. Read the {@code skipped} count rather than
     * {@code sent} to tell a correct repeat from a run that found nothing to do.
     *
     * <p>Each reminder is its own transaction, so a single bad address is counted in
     * {@code failed} rather than rolling back the batch. A 200 with failures is therefore a
     * real outcome, not a contradiction.
     */
    @PostMapping("/fee-reminders")
    @Operation(summary = "Send fee reminders for a day, now",
            description = "The same work the nightly cron does. Running it twice for the same date sends "
                    + "nothing the second time -- the duplicate is refused by a unique constraint, not "
                    + "by application state.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Ran, with examined/sent/skipped/failed counts"),
            @ApiResponse(responseCode = "400",
                    description = "MALFORMED_REQUEST when the date is not an ISO yyyy-MM-dd value",
                    content = @Content)})
    public FeeReminderRunResponse runFeeReminders(
            @Parameter(description = "The day to act for; defaults to today in UTC, as the cron does")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return feeReminderService.run(orToday(date));
    }

    @GetMapping("/fee-reminders")
    @Operation(summary = "How many reminders went out on a day",
            description = "The job's history for one date. A zero here alongside overdue invoices is the "
                    + "signal that a night was missed.")
    public FeeReminderDayCountResponse countFeeReminders(
            @Parameter(description = "The day to count; defaults to today in UTC")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = orToday(date);
        return new FeeReminderDayCountResponse(day, feeReminderService.countSentOn(day));
    }

    /**
     * Runs the absence scan now.
     *
     * <p>Freely repeatable, and more so than the reminder run: the scan recomputes rather
     * than sends, so running it twice reaches the same state from the same data. That is why
     * it is a single transaction where the reminder job is one transaction per invoice -- a
     * partial scan costs nothing because the next one starts over.
     *
     * <p>A POST despite being idempotent in effect. It writes -- raising, extending and
     * closing alerts -- and a GET that mutated the alert table would be cached, prefetched,
     * and retried by things that have no idea they are triggering a job.
     */
    @PostMapping("/absence-scans")
    @Operation(summary = "Scan for runs of absence, now",
            description = "Recomputes alerts against the register: raises new ones, extends continuing "
                    + "ones, and closes those whose student has come back. Repeatable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Scanned, with raised/extended/closed counts"),
            @ApiResponse(responseCode = "400",
                    description = "MALFORMED_REQUEST when the date is not an ISO yyyy-MM-dd value",
                    content = @Content)})
    public AbsenceScanResultResponse runAbsenceScan(
            @Parameter(description = "The day to scan as at; defaults to today in UTC, as the cron does")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return absenceAlertService.scan(orToday(date));
    }

    /**
     * Today in UTC when the caller named no date.
     *
     * <p>Deliberately not {@code LocalDate.now()}. The jobs read their date in UTC and fire
     * on a UTC cron, so this has to agree with them or a manual run and a scheduled one
     * could act for different days -- see this class's header for why that matters to
     * {@code uq_fee_reminders_per_day}.
     */
    private static LocalDate orToday(LocalDate date) {
        return date != null ? date : LocalDate.now(ZoneOffset.UTC);
    }
}
