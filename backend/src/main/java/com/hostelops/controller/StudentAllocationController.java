package com.hostelops.controller;

import com.hostelops.dto.allocation.AllocationResponse;
import com.hostelops.service.AllocationService;
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
 * A student's own allocation and residency history.
 *
 * <p>These routes do take an id, because both a student and an admin use them: the
 * portal reads its own, and an admin handling a support call reads someone else's.
 * That makes an ownership check unavoidable, and it lives in
 * {@code AllocationService} as {@code AccessScope.requireSelf} rather than here --
 * so a second controller reaching the same service cannot skip it.
 *
 * <p>A student asking for another student's allocation gets 403 {@code OUT_OF_SCOPE},
 * not the 404 the warden endpoints return for an out-of-hostel row. The difference
 * is what a 404 would be hiding: a warden must not learn that another hostel's ids
 * exist, whereas every student already knows other students exist, so there is
 * nothing to conceal and "you may not do this" is the more useful answer.
 */
@RestController
@RequestMapping("/api/v1/student/allocations")
@Tag(name = "My allocation (student)")
public class StudentAllocationController {

    private final AllocationService allocationService;

    public StudentAllocationController(AllocationService allocationService) {
        this.allocationService = allocationService;
    }

    @GetMapping("/{studentId}")
    @Operation(summary = "A student's current allocation")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "403",
                    description = "OUT_OF_SCOPE when a student asks for someone else's allocation",
                    content = @Content),
            @ApiResponse(responseCode = "409",
                    description = "STUDENT_NOT_ALLOCATED when the student holds no room",
                    content = @Content)})
    public AllocationResponse current(@PathVariable Long studentId) {
        return allocationService.currentForStudent(studentId);
    }

    @GetMapping("/{studentId}/history")
    @Operation(summary = "A student's residency history, newest first",
            description = "Vacated allocations are kept rather than deleted, so this is the full record of "
                    + "who lived where and when.")
    public List<AllocationResponse> history(@PathVariable Long studentId) {
        return allocationService.historyForStudent(studentId);
    }
}
