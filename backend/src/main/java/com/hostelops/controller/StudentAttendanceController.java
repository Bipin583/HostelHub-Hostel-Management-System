package com.hostelops.controller;

import com.hostelops.dto.attendance.AttendanceResponse;
import com.hostelops.dto.attendance.StudentAttendanceSummaryResponse;
import com.hostelops.service.AttendanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A student's own attendance.
 *
 * <p>Read-only, and pointedly so. A student may see every mark against their name and
 * change none of them: the two write routes live on {@link AttendanceController} behind
 * the warden gate, so there is no path by which the person being marked absent can mark
 * themselves present.
 *
 * <p>These routes take a student id and therefore need an ownership check, which is
 * {@code AccessScope.requireSelf} inside {@link AttendanceService} -- the same service
 * methods the warden controller calls. One implementation, checked once, reachable from
 * both sides: a warden passes any id in their scope, a student passes their own or gets
 * 403. Duplicating the read here with a "trust me, it's mine" variant is exactly the
 * second code path that eventually forgets the check.
 */
@RestController
@RequestMapping("/api/v1/student/attendance")
@Tag(name = "My attendance (student)")
public class StudentAttendanceController {

    private final AttendanceService attendanceService;

    public StudentAttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @GetMapping("/{studentId}")
    @Operation(summary = "My marks over a window, newest first")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found, possibly empty"),
            @ApiResponse(responseCode = "403",
                    description = "OUT_OF_SCOPE when a student asks for somebody else's attendance",
                    content = @Content),
            @ApiResponse(responseCode = "400",
                    description = "ATTENDANCE_DATE_INVALID for an inverted or over-long window",
                    content = @Content)})
    public List<AttendanceResponse> history(
            @PathVariable Long studentId,
            @Parameter(description = "First day of the window, inclusive", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last day of the window, inclusive", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendanceService.historyForStudent(studentId, from, to);
    }

    @GetMapping("/{studentId}/summary")
    @Operation(summary = "My attendance rate over a window",
            description = "The same figure the hostel office sees, from the same query -- so a student "
                    + "querying their own rate and a warden reviewing it cannot get different answers.")
    public StudentAttendanceSummaryResponse summary(
            @PathVariable Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendanceService.summaryForStudent(studentId, from, to);
    }
}
