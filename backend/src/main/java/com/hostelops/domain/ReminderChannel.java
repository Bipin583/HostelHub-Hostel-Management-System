package com.hostelops.domain;

/**
 * How a reminder was delivered.
 *
 * <p>Recorded per reminder rather than assumed, because the idempotency guarantee
 * is per fee per day and not per channel: if SMS is added later, "we already sent
 * today" has to keep meaning what it meant. The column has no CHECK constraint, so
 * adding a channel is a code change alone.
 */
public enum ReminderChannel {
    EMAIL,
    SMS
}
