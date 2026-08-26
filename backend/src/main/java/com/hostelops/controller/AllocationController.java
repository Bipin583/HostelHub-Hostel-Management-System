package com.hostelops.controller;

import com.hostelops.dto.allocation.AllocationResponse;
import com.hostelops.dto.allocation.AutoAllocationRequest;
import com.hostelops.dto.allocation.ManualAllocationRequest;
import com.hostelops.service.AllocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Allocation endpoints.
 *
 * <p>Under {@code /warden/} so the role gate in {@code SecurityConfig} covers
 * them by URL. Row-level scoping is not repeated here -- the service resolves
 * every student and room through a scoped finder, so a controller cannot forget
 * it.
 */
@RestController
@RequestMapping("/api/v1/warden/allocations")
@Tag(name = "Room allocation")
public class AllocationController {

    private final AllocationService allocationService;

    public AllocationController(AllocationService allocationService) {
        this.allocationService = allocationService;
    }

    @GetMapping
    @Operation(summary = "Active allocations within the caller's hostel scope")
    public Page<AllocationResponse> listActive(@PageableDefault(size = 20) Pageable pageable) {
        return allocationService.listActive(pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Allocate a specific room to a student")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Allocated"),
            @ApiResponse(responseCode = "409", description =
                    "ROOM_FULL when the last bed went to a concurrent request; "
                            + "STUDENT_ALREADY_ALLOCATED when the student holds a room; "
                            + "ROOM_NOT_ELIGIBLE when year or gender does not match",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "404", description = "Student or room is not in the caller's scope",
                    content = @io.swagger.v3.oas.annotations.media.Content)})
    public AllocationResponse allocateManually(@Valid @RequestBody ManualAllocationRequest request) {
        return allocationService.allocateManually(request.studentId(), request.roomId());
    }

    @PostMapping("/auto")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Allocate the best matching room automatically",
            description = "Matches on eligible gender, eligible year and a free bed, preferring "
                    + "partly-filled rooms so fewer rooms are left half empty.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Allocated"),
            @ApiResponse(responseCode = "409", description =
                    "NO_ROOM_AVAILABLE when nothing eligible has a free bed; "
                            + "STUDENT_ALREADY_ALLOCATED when the student holds a room",
                    content = @io.swagger.v3.oas.annotations.media.Content)})
    public AllocationResponse allocateAutomatically(@Valid @RequestBody AutoAllocationRequest request) {
        return allocationService.allocateAutomatically(request.studentId());
    }

    @DeleteMapping("/student/{studentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Vacate a student's room",
            description = "Frees the bed and keeps the allocation row as residency history.")
    public void vacate(@PathVariable Long studentId) {
        allocationService.vacate(studentId);
    }

    @GetMapping("/student/{studentId}")
    @Operation(summary = "A student's current allocation")
    public AllocationResponse current(@PathVariable Long studentId) {
        return allocationService.currentForStudent(studentId);
    }

    @GetMapping("/student/{studentId}/history")
    @Operation(summary = "A student's full residency history, newest first")
    public List<AllocationResponse> history(@PathVariable Long studentId) {
        return allocationService.historyForStudent(studentId);
    }
}
