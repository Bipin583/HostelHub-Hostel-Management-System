package com.hostelops.dto.allocation;

import com.hostelops.dto.room.RoomSummaryResponse;
import com.hostelops.dto.student.StudentSummaryResponse;
import java.time.Instant;

public record AllocationResponse(
        Long id,
        StudentSummaryResponse student,
        RoomSummaryResponse room,
        boolean active,
        Instant allocatedAt,
        Instant vacatedAt) {
}
