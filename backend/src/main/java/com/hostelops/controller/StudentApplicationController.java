package com.hostelops.controller;

import com.hostelops.dto.application.ApplicationResponse;
import com.hostelops.service.ApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A student's own applications.
 *
 * <p>{@code POST} with no body and no id: everything the operation needs -- who is
 * applying -- is in the token. There is nothing here for a client to get wrong and
 * nothing for the server to check beyond the lifecycle state.
 */
@RestController
@RequestMapping("/api/v1/student/applications")
@Tag(name = "My applications (student)")
public class StudentApplicationController {

    private final ApplicationService applicationService;

    public StudentApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Apply for accommodation")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Application submitted and awaiting a warden"),
            @ApiResponse(responseCode = "409", description =
                    "DUPLICATE_APPLICATION when one is already awaiting a decision; "
                            + "STUDENT_ALREADY_ALLOCATED when the student already holds a room",
                    content = @Content)})
    public ApplicationResponse apply() {
        return applicationService.apply();
    }

    @GetMapping
    @Operation(summary = "My applications, newest first",
            description = "Includes rejected ones and the reason given, so the student can see why.")
    public List<ApplicationResponse> myApplications() {
        return applicationService.myApplications();
    }
}
