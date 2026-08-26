package com.hostelops.dto.student;

import com.hostelops.domain.AllocationStatus;
import com.hostelops.domain.Gender;
import com.hostelops.dto.room.RoomSummaryResponse;

/**
 * A student's full record, including where they currently live.
 *
 * <p>{@code currentRoom} is derived from the active allocation and is null when
 * there is none. It is not a stored field on the student -- see the note on
 * {@code Student} about the column that used to drift.
 */
public record StudentDetailResponse(
        Long id,
        String rollNumber,
        String fullName,
        String email,
        Gender gender,
        Integer yearOfStudy,
        String branch,
        String mobileNo,
        String parentMobileNo,
        AllocationStatus allocationStatus,
        RoomSummaryResponse currentRoom) {
}
