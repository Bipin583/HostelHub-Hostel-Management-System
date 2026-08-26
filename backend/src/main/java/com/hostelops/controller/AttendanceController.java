package com.hostelops.controller;

import com.hostelops.dto.attendance.AttendanceBulkMarkRequest;
import com.hostelops.dto.attendance.AttendanceBulkMarkResponse;
import com.hostelops.dto.attendance.AttendanceMarkRequest;
import com.hostelops.dto.attendance.AttendanceResponse;
import com.hostelops.dto.attendance.AttendanceTrendResponse;
import com.hostelops.dto.attendance.StudentAttendanceSummaryResponse;
import com.hostelops.service.AttendanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Taking and reading the register.
 *
 * <p>Two ways to write, and both are here on purpose. {@code POST /register} submits a
 * whole day at once, which is how roll call actually happens and which makes the day one
 * transaction; {@code POST /mark} flips a single student, which is how the inevitable
 * correction happens. Without the second, a warden fixing one student would have to
 * resubmit the whole register -- and a client that finds that awkward starts sending
 * partial registers, which is the one state the absence detector cannot read, because a
 * student nobody marked is indistinguishable from a day nobody took.
 *
 * <p>No date defaults to today anywhere on this controller. The register is routinely
 * completed in the evening and corrected the next morning, so "which day" is always the
 * caller's to state; a server-side {@code now()} would quietly make yesterday's missed
 * roll call unrecordable. Whether a given date may be marked at all is the service's
 * decision, not a binding rule -- a future date is {@code ATTENDANCE_DATE_INVALID}.
 */
@RestController
@RequestMapping("/api/v1/warden/attendance")
@Tag(name = "Attendance (warden)")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @GetMapping("/register")
    @Operation(summary = "One day's register for the students the caller may see")
    public Page<AttendanceResponse> register(
            @Parameter(description = "The day to read, ISO yyyy-MM-dd", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PageableDefault(size = 20) Pageable pageable) {
        return attendanceService.register(date, pageable);
    }

    /**
     * Submits a whole day's roll call.
     *
     * <p>Ids the caller may not see come back in {@code skippedStudentIds} rather than
     * failing the request, and a student who cannot be seen is reported identically to one
     * who does not exist -- distinguishing them would turn this into a roster probe.
     * Re-submitting an unchanged register reports zero changed rather than everything
     * updated, which is what makes the counts worth reading.
     */
    @PostMapping("/register")
    @Operation(summary = "Mark a whole register in one transaction",
            description = "One request, one date, up to 1000 students. Marking the same register twice is "
                    + "safe: rows that already say what the submission says are left untouched.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Marked, with created/updated/skipped counts"),
            @ApiResponse(responseCode = "400", description =
                    "ATTENDANCE_DATE_INVALID for a date that has not happened yet; VALIDATION_FAILED for an "
                            + "empty or over-large batch",
                    content = @Content)})
    public AttendanceBulkMarkResponse markRegister(@Valid @RequestBody AttendanceBulkMarkRequest request) {
        return attendanceService.markRegister(request);
    }

    @PostMapping("/mark")
    @Operation(summary = "Mark or correct one student for one date",
            description = "Idempotent in effect: marking a student who is already marked rewrites the "
                    + "existing row and records who changed it, rather than adding a second one.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Marked"),
            @ApiResponse(responseCode = "400", description = "ATTENDANCE_DATE_INVALID for a future date",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "The student is not in the caller's scope",
                    content = @Content),
            @ApiResponse(responseCode = "409",
                    description = "DUPLICATE_RESOURCE when a concurrent request marked the same student and "
                            + "date first (uq_attendance_student_date)",
                    content = @Content)})
    public AttendanceResponse markOne(@Valid @RequestBody AttendanceMarkRequest request) {
        return attendanceService.markOne(request);
    }

    @GetMapping("/trend")
    @Operation(summary = "Daily head counts over a window, for the trend chart",
            description = "Days nobody marked are absent from the series rather than reported as zero "
                    + "present -- a register that was never taken is not a day everybody missed.")
    public AttendanceTrendResponse trend(
            @Parameter(description = "First day of the window, inclusive", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last day of the window, inclusive", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendanceService.trend(from, to);
    }

    @GetMapping("/student/{studentId}")
    @Operation(summary = "One student's marks over a window, newest first")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found, possibly empty"),
            @ApiResponse(responseCode = "400",
                    description = "ATTENDANCE_DATE_INVALID when the window is inverted or longer than the "
                            + "service reports on",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "The student is not in the caller's scope",
                    content = @Content)})
    public List<AttendanceResponse> historyForStudent(
            @PathVariable Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendanceService.historyForStudent(studentId, from, to);
    }

    @GetMapping("/student/{studentId}/summary")
    @Operation(summary = "One student's attendance rate over a window",
            description = "Counted in the database rather than by loading the rows, so the same two "
                    + "queries answer a week and a semester.")
    public StudentAttendanceSummaryResponse summaryForStudent(
            @PathVariable Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendanceService.summaryForStudent(studentId, from, to);
    }
}
