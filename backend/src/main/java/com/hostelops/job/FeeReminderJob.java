package com.hostelops.job;

import com.hostelops.service.FeeReminderService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the nightly fee reminder run.
 *
 * <h2>A trigger and nothing else</h2>
 *
 * <p>This class decides when, and what "today" means. It decides nothing about reminders --
 * no lead time, no filtering, no counting. That is deliberate: everything worth testing about
 * this feature lives in {@link FeeReminderService}, and a job with logic in it is logic that
 * can only be exercised by waiting for a cron to fire. {@code FeeReminderIdempotencyIT} calls
 * the service twice and asserts the second run sends nothing, which is a test that could not
 * be written against a scheduled method.
 *
 * <p>It also means the same run is reachable two ways -- the cron here, and the admin
 * endpoint that triggers it by hand -- with no chance of the two behaving differently,
 * because there is only one implementation and this is not it.
 *
 * <h2>Safe to fire more than once</h2>
 *
 * <p>Nothing here coordinates with other instances, and nothing needs to. Run three copies of
 * this application and all three will fire this cron; {@code uq_fee_reminders_per_day} means
 * the invoices still get one reminder each. That property is what makes
 * {@code app.scheduling.enabled} a deployment preference rather than a correctness
 * requirement, and it is argued at {@code SchedulingProperties}.
 *
 * <h2>UTC on both halves</h2>
 *
 * <p>The trigger's zone and the date it passes are both UTC, and they have to agree. A cron
 * firing in the JVM's local zone while the run date came from
 * {@code LocalDate.now(ZoneOffset.UTC)} would, for a few hours either side of midnight, ask
 * for reminders dated a day away from the day the trigger meant -- and since the reminder log
 * is keyed by date, that is a second reminder for invoices already reminded, produced by
 * nothing more than a timezone disagreement between two lines of the same class.
 */
@Component
public class FeeReminderJob {

    private static final Logger log = LoggerFactory.getLogger(FeeReminderJob.class);

    private final FeeReminderService reminders;

    public FeeReminderJob(FeeReminderService reminders) {
        this.reminders = reminders;
    }

    /**
     * Runs the reminders for today.
     *
     * <p>Exceptions are caught here rather than left to the scheduler's error handler. The
     * default handler logs and carries on, which is the right behaviour, but it logs without
     * saying which job or which date -- and a failed reminder run is something somebody has
     * to be able to find in a log a week later.
     *
     * <p>The run itself already contains one failure per invoice, so anything reaching this
     * catch failed before or around the loop: the batch query, or the audit write. Both are
     * "the whole run did not happen", and the recovery is the next night's run -- there is
     * nothing to undo, because the reminders that did go out are individually committed and
     * individually recorded.
     */
    @Scheduled(cron = "${app.scheduling.fee-reminder-cron}", zone = "UTC")
    public void run() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            reminders.run(today);
        } catch (RuntimeException e) {
            log.error("The fee reminder run for {} did not complete", today, e);
        }
    }
}
