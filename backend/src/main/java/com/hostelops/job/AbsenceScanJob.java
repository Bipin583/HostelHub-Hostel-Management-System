package com.hostelops.job;

import com.hostelops.service.AbsenceAlertService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the nightly absence scan.
 *
 * <p>A trigger and nothing else, for the reasons set out on {@link FeeReminderJob}: the
 * detection logic is in {@link AbsenceAlertService} where a test can call it with a fixture
 * date, and this class only decides when and what today is. Both jobs read their date in UTC
 * and fire on a UTC cron, which is the agreement {@link FeeReminderJob} explains.
 *
 * <p>Scheduled ahead of the reminder run, and the order is not arbitrary -- though nothing
 * breaks if it is reversed. The scan is the cheaper of the two and the one a warden looks at
 * first thing, so it goes first; the reminders follow while the office is still opening.
 * Neither reads what the other writes, so they are independent, which is why they are two
 * crons rather than one sequence.
 *
 * <p>Unlike the reminder run, this scan is a single transaction: it can be re-run freely
 * because it recomputes rather than sends, so losing a partial run costs nothing. That
 * asymmetry between the two jobs is argued at {@code FeeReminderService}.
 */
@Component
public class AbsenceScanJob {

    private static final Logger log = LoggerFactory.getLogger(AbsenceScanJob.class);

    private final AbsenceAlertService alerts;

    public AbsenceScanJob(AbsenceAlertService alerts) {
        this.alerts = alerts;
    }

    /**
     * Scans for runs of absence as at today.
     *
     * <p>Caught and logged with the date, for the same reason {@link FeeReminderJob#run} does
     * it. Here the whole scan is one transaction, so a failure means no alert was raised,
     * extended or closed tonight -- and the next run reaches the same state from the same
     * data, which is the property that makes doing nothing about it the right response.
     */
    @Scheduled(cron = "${app.scheduling.absence-scan-cron}", zone = "UTC")
    public void run() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            alerts.scan(today);
        } catch (RuntimeException e) {
            log.error("The absence scan for {} did not complete", today, e);
        }
    }
}
