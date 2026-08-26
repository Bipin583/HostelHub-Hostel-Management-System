package com.hostelops.repository;

import java.time.LocalDate;

/** Present and absent head counts for one day. Result of an aggregate; not an entity. */
public record AttendanceDayTotals(LocalDate date, long present, long absent) {

    public long marked() {
        return present + absent;
    }
}
