package com.hostelops.dto.fee;

import java.time.LocalDate;

/**
 * How many fee reminders went out on one day.
 *
 * <p>A record rather than a bare {@code long}, even though it carries a single number. A
 * response body of {@code 5} is not extensible -- the first thing anybody wants beside it
 * is which day it counts, and by then the shape has to change and every client with it.
 *
 * <p>Echoing {@code reminderDate} back is the point of the type. The endpoint defaults the
 * date to today in UTC, which is not necessarily the caller's today, so a response that
 * only said "5" would be ambiguous for exactly the users most likely to be reading it at
 * midnight. Stating the date the count belongs to makes the answer self-describing, and
 * mirrors {@link FeeReminderRunResponse}, which reports the date it acted for the same way.
 */
public record FeeReminderDayCountResponse(LocalDate reminderDate, long sent) {
}
