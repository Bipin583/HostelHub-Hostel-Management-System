package com.hostelops.controller;

import com.hostelops.dto.student.StudentDetailResponse;
import com.hostelops.dto.student.StudentProfileUpdateRequest;
import com.hostelops.dto.student.StudentSummaryResponse;
import com.hostelops.service.StudentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The student's own record.
 *
 * <p>Not one of these routes takes a student id. The id comes from the JWT
 * subject, so there is nothing in the request to tamper with and no ownership
 * check to forget -- the safest kind of authorization is the kind the URL cannot
 * express. Compare {@link StudentAllocationController}, where an id <em>is</em>
 * accepted and therefore has to be checked.
 */
@RestController
@RequestMapping("/api/v1/student/me")
@Tag(name = "My profile (student)")
public class StudentSelfController {

    private final StudentService studentService;

    public StudentSelfController(StudentService studentService) {
        this.studentService = studentService;
    }

    @GetMapping
    @Operation(summary = "My student record, with my current room if I hold one")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "403",
                    description = "The authenticated account is not a student (an admin, for instance)",
                    content = @Content)})
    public StudentDetailResponse me() {
        return studentService.me();
    }

    /**
     * Updates the fields a student owns.
     *
     * <p>{@link StudentProfileUpdateRequest} carries three fields and no more. Roll
     * number, gender, year of study and allocation status are absent from the DTO,
     * so a student cannot promote themselves into a different year -- and thereby
     * into a different room's eligibility -- by adding a field to the JSON. Mass
     * assignment is closed off by the shape of the type rather than by a check
     * someone has to remember to write.
     */
    @PutMapping
    @Operation(summary = "Update my contact details")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED with the offending fields",
                    content = @Content)})
    public StudentDetailResponse updateMyProfile(@Valid @RequestBody StudentProfileUpdateRequest request) {
        return studentService.updateMyProfile(request);
    }

    @GetMapping("/roommates")
    @Operation(summary = "The other students in my room",
            description = "409 STUDENT_NOT_ALLOCATED when I do not currently hold a room -- an empty list "
                    + "would be ambiguous between 'no roommates' and 'no room'.")
    public List<StudentSummaryResponse> roommates() {
        return studentService.roommates();
    }
}
