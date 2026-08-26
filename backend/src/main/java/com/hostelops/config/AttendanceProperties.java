package com.hostelops.config;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Attendance and absence-alert settings.
 *
 * <p>The threshold and the calendar are configuration rather than constants
 * because they are institutional policy, not engineering decisions: a hostel that
 * counts absences over a six-day week, or shuts for a fortnight in December,
 * changes a value here and nothing else.
 *
 * <p>{@code weekendDays} and {@code holidays} exist because "10 consecutive absent
 * days" has to mean ten days a student was expected to be present. Counting
 * calendar days would raise an alert on every student after a long break, which is
 * how an alerting feature gets muted and then ignored.
 */
@ConfigurationProperties(prefix = "app.attendance")
public record AttendanceProperties(
        int absenceAlertThreshold,
        Set<DayOfWeek> weekendDays,
        Set<LocalDate> holidays) {

    public AttendanceProperties {
        if (absenceAlertThreshold < 1) {
            throw new IllegalArgumentException(
                    "app.attendance.absence-alert-threshold must be at least 1, was " + absenceAlertThreshold);
        }
        weekendDays = weekendDays == null ? Set.of() : Set.copyOf(weekendDays);
        holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
    }

    /** True when a student was expected to be present on this date. */
    public boolean isWorkingDay(LocalDate date) {
        return !weekendDays.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }
}
