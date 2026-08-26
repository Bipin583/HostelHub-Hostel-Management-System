package com.hostelops.controller;

import com.hostelops.dto.room.OccupancyResponse;
import com.hostelops.dto.room.RoomResponse;
import com.hostelops.dto.student.StudentSummaryResponse;
import com.hostelops.service.RoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
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
 * Rooms, as wardens and admins see them.
 *
 * <p>An LH warden asking for a BH room gets a 404, not a filtered-out row and not
 * a 403 -- the same reasoning as students: revealing that the id is real would let
 * a warden enumerate the other hostel's room list.
 */
@RestController
@RequestMapping("/api/v1/warden/rooms")
@Tag(name = "Rooms (warden)")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping
    @Operation(summary = "List rooms in the caller's hostel scope, with live bed counts",
            description = "Occupancy is counted from active allocations at read time, never read from a "
                    + "stored counter, so it cannot drift out of step with the allocations themselves.")
    public Page<RoomResponse> list(
            @RequestParam(required = false) @Size(max = 20) String block,
            @PageableDefault(size = 20, sort = "roomName", direction = Sort.Direction.ASC) Pageable pageable) {

        return block == null || block.isBlank()
                ? roomService.list(pageable)
                : roomService.listByBlock(block.trim(), pageable);
    }

    @GetMapping("/{roomId}")
    @Operation(summary = "One room, with live bed counts")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404", description = "No such room, or it is in another hostel",
                    content = @Content)})
    public RoomResponse get(@PathVariable Long roomId) {
        return roomService.get(roomId);
    }

    @GetMapping("/{roomId}/occupants")
    @Operation(summary = "Students currently living in a room, by roll number")
    public List<StudentSummaryResponse> occupants(@PathVariable Long roomId) {
        return roomService.occupants(roomId);
    }

    @GetMapping("/occupancy")
    @Operation(summary = "Occupancy per hostel and block",
            description = "Aggregated in the database: one grouped query, not a table pulled into the "
                    + "application to be counted there.")
    public List<OccupancyResponse> occupancy() {
        return roomService.occupancyByBlock();
    }
}
