package com.hostelops.dto.audit;

import java.time.Instant;

/**
 * How many audited operations happened since an instant.
 *
 * <p>Echoes {@code since} back beside the count, for the reason
 * {@code FeeReminderDayCountResponse} gives at more length: the endpoint defaults the window
 * when the caller omits it, so a body carrying only a number would not say what window it
 * counts. Stating the boundary makes the figure self-describing.
 *
 * <p>Named {@code events} rather than {@code actions} because that is what the rows are --
 * one per audited method invocation, including the ones the schedulers performed with no
 * actor. A count of "actions by people" would be a different and smaller number, and this is
 * not it.
 */
public record AuditActivityCountResponse(Instant since, long events) {
}
