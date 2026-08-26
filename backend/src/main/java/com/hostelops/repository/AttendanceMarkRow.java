package com.hostelops.repository;

import com.hostelops.domain.AttendanceStatus;
import java.time.LocalDate;

/**
 * One attendance mark, flattened to the three fields the streak detector reads.
 *
 * <p>A record rather than a Spring Data interface projection because this is a JPQL
 * constructor expression, which needs a real constructor to call.
 */
public record AttendanceMarkRow(Long studentId, LocalDate date, AttendanceStatus status) {

    public boolean isAbsent() {
        return status == AttendanceStatus.ABSENT;
    }
}
