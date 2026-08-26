package com.hostelops.dto.attendance;

import java.time.LocalDate;

/**
 * One student's attendance over a window.
 *
 * <p>{@code markedDays} is reported next to the percentage on purpose. A student with
 * 100% attendance over two marked days has not attended well; they have barely been
 * recorded. Without the denominator the percentage invites exactly that misreading,
 * and it is the number a disciplinary conversation would be based on.
 *
 * <p>The percentage is computed over marked days only -- days the register was never
 * taken are not held against anyone.
 */
public record StudentAttendanceSummaryResponse(
        Long studentId,
        LocalDate from,
        LocalDate to,
        long markedDays,
        long presentDays,
        long absentDays,
        double presentPercentage) {

    public static StudentAttendanceSummaryResponse of(
            Long studentId, LocalDate from, LocalDate to, long presentDays, long absentDays) {
        long marked = presentDays + absentDays;
        // Zero rather than a division by zero. A window with no marks has no
        // attendance rate, and reporting 100% for it would read as perfect.
        double percentage = marked == 0 ? 0d : (presentDays * 100d) / marked;
        return new StudentAttendanceSummaryResponse(
                studentId, from, to, marked, presentDays, absentDays, percentage);
    }
}
