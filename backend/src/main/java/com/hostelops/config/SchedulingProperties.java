package com.hostelops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Background job schedule.
 *
 * <p>{@code enabled} is a real switch, not a convenience. Integration tests turn
 * it off so a cron trigger cannot fire in the middle of an assertion, and the jobs
 * are then invoked directly -- which is also the only way to assert that running
 * one twice changes nothing.
 *
 * <p>It matters in production too. Run more than one instance of this application
 * and every instance will fire the same cron, so the jobs have to be safe to
 * duplicate (they are -- see {@code uq_fee_reminders_per_day} and
 * {@code uq_absence_alert_streak}) or confined to one instance by turning this off
 * everywhere else. Correctness does not depend on which you choose; the constraint
 * is what guarantees it.
 */
@ConfigurationProperties(prefix = "app.scheduling")
public record SchedulingProperties(
        boolean enabled,
        String feeReminderCron,
        String absenceScanCron) {
}
