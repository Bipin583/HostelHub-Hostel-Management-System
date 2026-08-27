package com.hostelops.service;

import com.hostelops.audit.AuditEntity;
import com.hostelops.audit.Audited;
import com.hostelops.domain.AuditAction;
import com.hostelops.domain.HostelFee;
import com.hostelops.dto.fee.FeeReminderRunResponse;
import com.hostelops.repository.FeeReminderRepository;
import com.hostelops.repository.HostelFeeRepository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The nightly fee reminder run.
 *
 * <h2>The guarantee, and where it lives</h2>
 *
 * <p>One reminder per invoice per day, however many times this runs and however it dies
 * halfway. The whole of that is {@code uq_fee_reminders_per_day}, argued on
 * {@link com.hostelops.domain.FeeReminder}: the job holds no state, needs no lock, and does
 * not care whether another instance is running beside it. What this class contributes is the
 * transaction shape that lets the constraint do its work per invoice instead of per batch.
 *
 * <h2>Why the run is not transactional</h2>
 *
 * <p>Because Postgres aborts a transaction on a constraint violation. One transaction around
 * the whole run would mean the first invoice that was already reminded today takes every
 * other invoice down with it -- the guarantee's own mechanism becoming the thing that stops
 * the job. So each reminder is its own transaction, in {@link FeeReminderDispatch}, and this
 * method is the loop around them with no transaction of its own to poison.
 *
 * <p>That is the opposite of the choice {@code AbsenceAlertService.scan} makes, and the
 * difference is worth stating. A scan that dies partway can simply be re-run: it recomputes
 * the same alerts and reaches the same state, so losing the batch costs nothing but time.
 * A reminder run that dies partway has already sent mail, and re-running it must not resend.
 * Recoverability is what decides the boundary, not consistency in the abstract.
 *
 * <h2>Two ways it skips</h2>
 *
 * <p>Cheaply, from {@link FeeReminderRepository#findFeeIdsRemindedOn} -- one query telling
 * the loop which of this batch is already done. And correctly, from the constraint, when
 * another instance inserts between that read and the write. The first is an optimisation and
 * carries no guarantee; deleting it would leave the job correct and slower. Both are counted
 * as skips, because from the outside they are the same event.
 */
@Service
public class FeeReminderService {

    private static final Logger log = LoggerFactory.getLogger(FeeReminderService.class);

    /**
     * How far ahead of a due date an invoice starts being reminded.
     *
     * <p>A week, so a reminder arrives while there is still time to act on one. Everything
     * already overdue is in scope too -- the query is {@code dueDate <= runDate + 7} -- which
     * means an unpaid invoice is reminded every day from a week before it is due until it is
     * paid or cancelled.
     *
     * <p>A constant rather than a configuration property, for the reason
     * {@code AbsenceAlertService.READ_BACK_DAYS} is one: nobody has asked for it to vary, and
     * a property that is never set is a property whose default is the real answer with an
     * extra place to look for it.
     */
    private static final int REMINDER_LEAD_DAYS = 7;

    private final HostelFeeRepository fees;
    private final FeeReminderRepository reminders;
    private final FeeReminderDispatch dispatch;

    public FeeReminderService(
            HostelFeeRepository fees,
            FeeReminderRepository reminders,
            FeeReminderDispatch dispatch) {
        this.fees = fees;
        this.reminders = reminders;
        this.dispatch = dispatch;
    }

    /**
     * Runs one night's reminders.
     *
     * <p>Takes the run date rather than reading a clock, so the job passes today and a test
     * passes whatever date makes its fixture meaningful -- the same reason
     * {@code AbsenceAlertService.scan} does.
     *
     * <p>Audited as an {@code UPDATE} with no entity id: a run touches many invoices and no
     * single one, and the {@link FeeReminderRunResponse} it returns lands in the event's
     * payload, so the trail says what every run did including the ones that sent nothing.
     * There is no ambient transaction for the audit write to join, so that row commits on its
     * own -- which is right for an event describing a batch rather than a row.
     *
     * <p>Deliberately unscoped: no {@code visibleGenders()} anywhere. This is a job, not a
     * request, and there is no warden behind it whose hostel would narrow it. The endpoint
     * that triggers it is what carries the authorization, and every reminder in the database
     * is due regardless of who happened to press the button.
     */
    @Audited(entity = AuditEntity.FEE_REMINDER, action = AuditAction.UPDATE)
    public FeeReminderRunResponse run(LocalDate runDate) {
        List<HostelFee> due = fees.findDueForReminder(runDate.plusDays(REMINDER_LEAD_DAYS));
        if (due.isEmpty()) {
            log.info("Fee reminder run {}: nothing due", runDate);
            return new FeeReminderRunResponse(runDate, 0, 0, 0, 0);
        }

        Set<Long> alreadyReminded = new HashSet<>(
                reminders.findFeeIdsRemindedOn(runDate, due.stream().map(HostelFee::getId).toList()));

        int sent = 0;
        int skipped = 0;
        int failed = 0;

        for (HostelFee fee : due) {
            if (alreadyReminded.contains(fee.getId())) {
                skipped++;
                continue;
            }
            switch (outcomeFor(fee.getId(), runDate)) {
                case SENT -> sent++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }

        log.info("Fee reminder run {}: {} invoices examined, {} sent, {} skipped, {} failed",
                runDate, due.size(), sent, skipped, failed);
        return new FeeReminderRunResponse(runDate, due.size(), sent, skipped, failed);
    }

    /**
     * One reminder, with anything it throws turned into a count.
     *
     * <p>The whole reason the run has no transaction of its own: a reminder that fails for a
     * reason nobody anticipated must cost one invoice, not the night. Its own transaction has
     * already rolled back by the time this catches anything, so there is no half-written row
     * to clean up here.
     *
     * <p>The duplicate is classified here rather than inside the transactional method that
     * provokes it, which reads backwards until you try it the other way round. A constraint
     * violation is detected by a flush, and a failed flush marks the transaction rollback-only;
     * a {@code catch} inside {@link FeeReminderDispatch#sendFor} therefore returns a value that
     * never survives its own commit -- Spring throws {@code UnexpectedRollbackException} on the
     * way out and the return is discarded. The classification has to happen outside the
     * transaction boundary because that is the first place the outcome can be observed at all.
     *
     * <p>{@link RuntimeException} rather than a list of the ones expected, because the point
     * is the unanticipated ones -- and it is logged with the invoice id, which is what turns
     * a {@code failed} count into something somebody can act on. {@link Error} is not caught:
     * an {@code OutOfMemoryError} is not a per-invoice problem and pretending otherwise would
     * have the loop press on through a dying JVM, reporting failures.
     */
    private FeeReminderDispatch.Outcome outcomeFor(Long feeId, LocalDate runDate) {
        try {
            return dispatch.sendFor(feeId, runDate);
        } catch (DataIntegrityViolationException e) {
            // uq_fee_reminders_per_day: another run claimed this invoice for today between the
            // findFeeIdsRemindedOn read above and the insert. The invoice is reminded exactly
            // once and this run simply lost the race, so it is the guarantee holding rather
            // than a fault -- counted with the cheap skips, because from the outside the two
            // are the same event. This is the branch a second run of the job takes for every
            // invoice, which is what FeeReminderIdempotencyIT asserts.
            log.debug("Fee {} was already reminded on {}", feeId, runDate);
            return FeeReminderDispatch.Outcome.SKIPPED;
        } catch (RuntimeException e) {
            log.error("Fee reminder for invoice {} failed on {}", feeId, runDate, e);
            return FeeReminderDispatch.Outcome.FAILED;
        }
    }

    /** How many reminders went out on a given day. For the admin view of the job's history. */
    @Transactional(readOnly = true)
    public long countSentOn(LocalDate date) {
        return reminders.countByReminderDate(date);
    }
}
