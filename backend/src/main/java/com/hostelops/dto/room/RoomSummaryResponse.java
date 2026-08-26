package com.hostelops.dto.room;

import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelType;

/** A room without occupancy, for embedding in an allocation. */
public record RoomSummaryResponse(
        Long id,
        String roomName,
        HostelType hostelType,
        String block,
        Integer floor,
        Integer capacity,
        Integer eligibleYear,
        Gender eligibleGender) {
}
