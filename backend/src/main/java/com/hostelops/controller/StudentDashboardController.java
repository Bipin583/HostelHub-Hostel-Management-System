package com.hostelops.controller;

import com.hostelops.dto.dashboard.StudentDashboardResponse;
import com.hostelops.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The resident landing page.
 *
 * <p>Like its staff counterpart, no id and no parameters: the student is the JWT subject.
 * The separate controller rather than a branch inside {@link DashboardController} follows the
 * split every pair here uses -- the route prefix is what {@code SecurityConfig} gates on, so
 * two audiences means two prefixes and therefore two classes.
 *
 * <p>One tile today. The response is a record precisely so that adding the second is not a
 * breaking change, and the fee tile that obviously belongs here is documented as absent on
 * {@link StudentDashboardResponse}: the staff unpaid count is scoped by gender rather than by
 * student, so reusing it would tell a resident how many of their peers owe money.
 */
@RestController
@RequestMapping("/api/v1/student/dashboard")
@Tag(name = "Dashboard (student)")
public class StudentDashboardController {

    private final DashboardService dashboardService;

    public StudentDashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @Operation(summary = "My landing tiles",
            description = "How many complaints I have filed that are still unresolved. Agrees with "
                    + "GET /api/v1/student/complaints, because it is the same predicate.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Computed"),
            @ApiResponse(responseCode = "403",
                    description = "The authenticated account is not a student",
                    content = @Content)})
    public StudentDashboardResponse dashboard() {
        return dashboardService.forStudent();
    }
}
