package com.hostelops.service;

import com.hostelops.domain.FeeReminder;
import com.hostelops.domain.HostelFee;
import com.hostelops.domain.ReminderChannel;
import com.hostelops.domain.Student;
import com.hostelops.domain.UserAccount;
import com.hostelops.notify.Notification;
import com.hostelops.notify.NotificationException;
import com.hostelops.notify.Notifier;
import com.hostelops.repository.FeeReminderRepository;
import com.hostelops.repository.HostelFeeRepository;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One invoice's reminder, in its own transaction.
 *
 * <h2>Why this is a separate bean</h2>
 *
 * <p>{@link FeeReminderService#run} must not be transactional -- one undeliverable address
 * cannot be allowed to roll back the whole night's work -- while each reminder inside it
 * must be, so that a duplicate insert rolls back only itself. Both facts are about the same
 * loop, and Spring's transactions are proxy-based, so a {@code @Transactional} method the
 * loop called on {@code this} would run with no transaction at all: the call never leaves
 * the object and never passes the proxy. Self-invocation would silently produce a job with
 * none of the isolation its correctness argument assumes.
 *
 * <p>A separate bean is the plain fix. {@code AopContext.currentProxy()} and self-injection
 * both work and both leave a reader wondering why, and the split says what is actually
 * true: the batch and the unit of work have different transactional lifetimes.
 *
 * <p>{@link Propagation#REQUIRES_NEW} rather than {@code REQUIRED}, so this holds even when
 * something else calls the job from inside a transaction -- an admin triggering a run
 * through the API, which is exactly how the endpoint is written. Under {@code REQUIRED} that
 * caller's transaction would enclose every reminder, the first duplicate would poison it,
 * and the per-invoice isolation would quietly be gone.
 *
 * <p>Package-private: this is {@link FeeReminderService}'s implementation, not API. Spring
 * proxies it regardless, and nothing outside this package should be sending a single
 * reminder without the run around it.
 */
@Component
class FeeReminderDispatch {

    private static final Logger log = LoggerFactory.getLogger(FeeReminderDispatch.class);

    private final HostelFeeRepository fees;
    private final FeeReminderRepository reminders;
    private final Notifier notifier;

    FeeReminderDispatch(HostelFeeRepository fees, FeeReminderRepository reminders, Notifier notifier) {
        this.fees = fees;
        this.reminders = reminders;
        this.notifier = notifier;
    }

    /**
     * Claims today's reminder for one invoice and sends it.
     *
     * <p>The invoice is re-read rather than passed in. The caller's copy was loaded outside
     * any transaction and is detached, and this method needs a managed instance to associate
     * the new row with. It also means the amounts in the message are the amounts as of now:
     * a student who paid while the batch was walking towards them gets no reminder rather
     * than a reminder for a balance they have already cleared.
     *
     * <p>That re-read costs three queries per reminder -- the invoice, then its student and
     * account lazily -- which partly re-pays the fetch joins the batch query already made.
     * Worth naming rather than hiding: the alternative is to associate the row with the
     * detached copy and take the message from the same object, which is one query instead of
     * three and rests on how Hibernate resolves a detached association. That is a claim the
     * integration suite can settle and this machine cannot, so the version that is correct
     * by construction is the one written here.
     *
     * <p>The insert is flushed before the send, which is the ordering
     * {@link FeeReminder} argues for. Claiming first means a crash after the flush loses a
     * message; sending first would mean a crash after the send resends it on the retry.
     *
     * @return what happened, for the counters in {@code FeeReminderRunResponse}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Outcome sendFor(Long feeId, LocalDate reminderDate) {
        HostelFee fee = fees.findById(feeId).orElse(null);
        if (fee == null || fee.isSettled() || fee.outstandingPaise() <= 0) {
            // Settled between the batch query and now, or deleted outright. Not a failure:
            // there was simply nothing left to remind anybody about.
            return Outcome.SKIPPED;
        }

        try {
            reminders.saveAndFlush(FeeReminder.forFeeOn(fee, reminderDate, ReminderChannel.EMAIL));
        } catch (DataIntegrityViolationException e) {
            // uq_fee_reminders_per_day: somebody already reminded this invoice today. This
            // is the guarantee working, so it is a skip rather than a failure -- and it is
            // the branch a second run of the job takes for every invoice, which is what
            // FeeReminderIdempotencyIT asserts. REQUIRES_NEW is what keeps the poisoned
            // transaction confined to this one invoice.
            log.debug("Fee {} was already reminded on {}", feeId, reminderDate);
            return Outcome.SKIPPED;
        }

        Student student = fee.getStudent();
        UserAccount account = student.getUser();
        try {
            notifier.send(new Notification(
                    account.getEmail(),
                    "Fee reminder: " + fee.getTitle() + " (" + fee.getAcademicYear() + ")",
                    ("""
                            %s (%s),

                            %s for %s, %s comes to %s. %s is outstanding and it was due on %s.

                            You can pay it from the fees page of the hostel portal. If you have \
                            already paid, no action is needed -- this notice was prepared before \
                            the payment reached us.""")
                            .formatted(
                                    account.getFullName(),
                                    student.getRollNumber(),
                                    fee.getTitle(),
                                    fee.getAcademicYear(),
                                    fee.getSemester(),
                                    rupees(fee.getAmountPaise()),
                                    rupees(fee.outstandingPaise()),
                                    fee.getDueDate())));
            return Outcome.SENT;
        } catch (NotificationException e) {
            // The row stays. It is the honest record: the decision to remind this invoice
            // today was taken, and the delivery of it failed -- which is a mail problem, not
            // a reason to remind again tomorrow and again the day after. Rolling the claim
            // back would turn every bad address into a daily retry loop.
            log.warn("Fee reminder for invoice {} could not be delivered", feeId, e);
            return Outcome.FAILED;
        }
    }

    /**
     * Paise as rupees, for a message a person reads.
     *
     * <p>Integer arithmetic on the paise, not a {@code double} divide. The reason money is
     * stored in paise in the first place is that binary floating point cannot hold 0.10, and
     * reintroducing it in the last step before a student reads the number would be a strange
     * place to give that up.
     */
    private static String rupees(long paise) {
        return "Rs. %d.%02d".formatted(paise / 100, Math.abs(paise % 100));
    }

    /**
     * What one reminder did.
     *
     * <p>Three outcomes and not a boolean, because {@code SKIPPED} is the interesting one --
     * the argument is on {@code FeeReminderRunResponse}. A run that skips everything is the
     * idempotency guarantee holding, and a run that finds nothing to do looks identical if
     * the only counter is successes.
     */
    enum Outcome {
        SENT,
        SKIPPED,
        FAILED
    }
}
