package com.hostelops.controller;

import com.hostelops.dto.attendance.AbsenceAlertResponse;
import com.hostelops.service.AbsenceAlertService;
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
 * A student's own open absence alerts.
 *
 * <p>Exists so the portal can answer "why has the office been trying to reach me" without
 * the student having to ask. That is the whole purpose, and it is why the route is
 * read-only: acknowledgement is a record of staff having seen the case, so a student
 * acknowledging their own alert would be the subject of a report signing it off.
 *
 * <p>Open alerts only, and no history route. Once an alert is closed -- because the student
 * came back -- there is nothing actionable in it for them, and a resident's absence history
 * is the kind of record that should be read by the office through
 * {@link AbsenceAlertController} rather than paged over by the person it describes.
 *
 * <p>The ownership check is {@code AccessScope.requireSelf} inside the service, on the same
 * method the warden side calls: a warden passes any student in their scope, a student
 * passes their own id or gets a 403. One check, one implementation, two callers -- the
 * pattern {@link StudentAttendanceController} argues at greater length.
 */
@RestController
@RequestMapping("/api/v1/student/absence-alerts")
@Tag(name = "My absence alerts (student)")
public class StudentAbsenceAlertController {

    private final AbsenceAlertService absenceAlertService;

    public StudentAbsenceAlertController(AbsenceAlertService absenceAlertService) {
        this.absenceAlertService = absenceAlertService;
    }

    @GetMapping("/{studentId}")
    @Operation(summary = "My open absence alerts",
            description = "Each carries how long the absence has run as at today. A list rather than a "
                    + "page: a student with more than a couple of open alerts has a problem the portal "
                    + "is not going to solve by paginating it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found, possibly empty"),
            @ApiResponse(responseCode = "403",
                    description = "OUT_OF_SCOPE when a student asks for somebody else's alerts",
                    content = @Content)})
    public List<AbsenceAlertResponse> openAlertsForStudent(@PathVariable Long studentId) {
        return absenceAlertService.openAlertsForStudent(studentId);
    }
}
