package com.hostelops.dto.attendance;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily present/absent totals over a window, for the trend chart.
 *
 * <p>Days with no register are absent from {@code days} rather than present as zeroes.
 * A gap in the line is the honest rendering of "nobody took the roll"; a zero would
 * draw a day on which the entire hostel was absent.
 */
public record AttendanceTrendResponse(LocalDate from, LocalDate to, List<Day> days) {

    public record Day(LocalDate date, long present, long absent, long marked) {
    }
}
