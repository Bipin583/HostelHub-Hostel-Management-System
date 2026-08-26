package com.hostelops.controller;

import com.hostelops.domain.AllocationStatus;
import com.hostelops.dto.student.StudentDetailResponse;
import com.hostelops.dto.student.StudentSummaryResponse;
import com.hostelops.repository.StudentSearchCriteria;
import com.hostelops.service.StudentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Student records, as wardens and admins see them.
 *
 * <p>The filters arrive as explicit request parameters rather than as a bound
 * command object. Two reasons: they show up individually in the OpenAPI document,
 * so the generated client gets three named arguments instead of an opaque blob;
 * and the caller's hostel scope is conspicuously <em>not</em> among them. Scope
 * comes from the signed token inside {@link StudentService} -- if it were a
 * parameter here, a client could ask for another hostel.
 */
@RestController
@RequestMapping("/api/v1/warden/students")
@Tag(name = "Students (warden)")
public class StudentController {

    private final StudentService studentService;

    public StudentController(StudentService studentService) {
        this.studentService = studentService;
    }

    @GetMapping
    @Operation(summary = "List students in the caller's hostel scope",
            description = "A warden sees only the gender their hostel houses; an admin sees everyone. "
                    + "Sortable by rollNumber, yearOfStudy, allocationStatus, branch, gender or id; "
                    + "unrecognised sort keys are ignored rather than rejected.")
    public Page<StudentSummaryResponse> list(
            @Parameter(description = "Year of study, 1-5")
            @RequestParam(required = false) @Min(1) @Max(5) Integer yearOfStudy,

            @Parameter(description = "Allocation lifecycle state")
            @RequestParam(required = false) AllocationStatus allocationStatus,

            @Parameter(description = "Case-insensitive partial match on roll number or full name")
            @RequestParam(required = false) @Size(max = 100) String query,

            @PageableDefault(size = 20, sort = "rollNumber", direction = Sort.Direction.ASC)
            Pageable pageable) {

        return studentService.list(new StudentSearchCriteria(yearOfStudy, allocationStatus, query), pageable);
    }

    @GetMapping("/{studentId}")
    @Operation(summary = "One student, with their current room if they hold one")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404",
                    description = "No such student, or the student belongs to another hostel. Both cases "
                            + "return 404 deliberately: a 403 would confirm the id exists and turn id "
                            + "enumeration into a roster leak.",
                    content = @Content)})
    public StudentDetailResponse get(@PathVariable Long studentId) {
        return studentService.get(studentId);
    }
}
