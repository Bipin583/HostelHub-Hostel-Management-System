package com.hostelops.dto.student;

import com.hostelops.domain.AllocationStatus;
import com.hostelops.domain.Gender;

public record StudentSummaryResponse(
        Long id,
        String rollNumber,
        String fullName,
        Gender gender,
        Integer yearOfStudy,
        String branch,
        AllocationStatus allocationStatus) {
}
