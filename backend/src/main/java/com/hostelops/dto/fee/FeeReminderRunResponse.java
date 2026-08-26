package com.hostelops.dto.fee;

import java.time.LocalDate;

/**
 * What one run of the fee-reminder job did.
 *
 * <p>{@code skipped} is reported separately from {@code sent} because it is the
 * interesting number. A second run on the same day should report every invoice as
 * skipped and none as sent -- that is {@code uq_fee_reminders_per_day} doing its job,
 * and it is what {@code FeeReminderIdempotencyIT} asserts. A response that only
 * counted successes would make the correct second run indistinguishable from a run
 * that found nothing to do.
 *
 * <p>{@code failed} counts invoices whose reminder threw for a reason that was not a
 * duplicate. Each reminder is its own transaction precisely so one bad address cannot
 * roll back the batch, which means failures have to be counted rather than propagated.
 */
public record FeeReminderRunResponse(
        LocalDate reminderDate, int invoicesExamined, int sent, int skipped, int failed) {
}
