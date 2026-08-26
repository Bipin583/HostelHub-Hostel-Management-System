package com.hostelops.dto.room;

import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelType;

/**
 * A room with live occupancy.
 *
 * <p>{@code occupiedBeds} is counted from active allocations at read time rather
 * than stored on the room. A stored counter is what let the predecessor report a
 * full room that had free beds, and no amount of care keeps two writers of the
 * same number in step.
 */
public record RoomResponse(
        Long id,
        String roomName,
        HostelType hostelType,
        String block,
        Integer floor,
        Integer capacity,
        Integer eligibleYear,
        Gender eligibleGender,
        long occupiedBeds,
        long freeBeds) {
}
