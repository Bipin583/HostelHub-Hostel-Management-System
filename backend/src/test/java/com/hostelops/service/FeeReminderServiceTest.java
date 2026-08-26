package com.hostelops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hostelops.domain.HostelFee;
import com.hostelops.dto.fee.FeeReminderRunResponse;
import com.hostelops.repository.FeeReminderRepository;
import com.hostelops.repository.HostelFeeRepository;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reminder run's counting, its failure isolation, and its transaction shape.
 *
 * <p>The guarantee itself -- one reminder per invoice per day, however many times the job
 * runs -- is {@code uq_fee_reminders_per_day} and cannot be tested here: a unique index
 * does not exist in a process with no database. {@code FeeReminderIdempotencyIT} proves it.
 *
 * <p>What this class contributes to that guarantee is the transaction shape, and that
 * <em>is</em> testable. The run holds no transaction of its own because Postgres aborts a
 * transaction on a constraint violation, so one transaction around the whole night would
 * let the first already-reminded invoice take every other invoice down with it -- the
 * guarantee's own mechanism becoming the thing that stops the job. Each reminder runs in
 * {@link FeeReminderDispatch} under {@code REQUIRES_NEW}, in a separate bean so Spring's
 * proxy is actually crossed. {@link StructuralClaims} asserts all three of those, because
 * a single-threaded mock test cannot observe a rollback boundary and the choice would
 * otherwise be invisible until it broke in production.
 *
 * <p>The rest is arithmetic that has to be right for the {@code skipped} count to mean
 * anything: a correct repeat of the job reports every invoice as skipped rather than sent,
 * and that is the number an operator reads to tell a repeat from a night that found
 * nothing to do.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeeReminderService")
class FeeReminderServiceTest {

    private static final LocalDate RUN_DATE = LocalDate.of(2026, 9, 23);

    @Mock private HostelFeeRepository fees;
    @Mock private FeeReminderRepository reminders;
    @Mock private FeeReminderDispatch dispatch;

    private FeeReminderService service;

    @BeforeEach
    void setUp() {
        service = new FeeReminderService(fees, reminders, dispatch);
    }

    @Nested
    @DisplayName("selecting what is due")
    class Selecting {

        @Test
        @DisplayName("looks a week ahead of the run date")
        void looksAWeekAhead() {
            when(fees.findDueForReminder(any())).thenReturn(List.of());

            service.run(RUN_DATE);

            // A reminder that arrives on the due date is a receipt, not a reminder.
            // Everything already overdue is in scope too, because the predicate is
            // dueDate <= runDate + 7 rather than an equality on one day.
            verify(fees).findDueForReminder(RUN_DATE.plusDays(7));
        }

        @Test
        @DisplayName("nothing due means nothing else is queried at all")
        void nothingDueQueriesNothingElse() {
            when(fees.findDueForReminder(any())).thenReturn(List.of());

            FeeReminderRunResponse response = service.run(RUN_DATE);

            assertThat(response.reminderDate()).isEqualTo(RUN_DATE);
            assertThat(response.invoicesExamined()).isZero();
            assertThat(response.sent()).isZero();
            assertThat(response.skipped()).isZero();
            assertThat(response.failed()).isZero();

            // The "already reminded" query takes the batch's ids as a parameter, so with an
            // empty batch it would be an `in ()` -- returning early avoids both that and a
            // pointless round trip on the many nights when nothing is due.
            verifyNoInteractions(reminders, dispatch);
        }

        @Test
        @DisplayName("asks which of this batch was already reminded, by id and for this date")
        void asksWhichOfTheBatchIsDone() {
            when(fees.findDueForReminder(any())).thenReturn(List.of(fee(1L), fee(2L), fee(3L)));
            when(reminders.findFeeIdsRemindedOn(eq(RUN_DATE), any())).thenReturn(List.of());
            when(dispatch.sendFor(anyLong(), eq(RUN_DATE))).thenReturn(FeeReminderDispatch.Outcome.SENT);

            service.run(RUN_DATE);

            // One query for the whole batch rather than one per invoice. This is the
            // optimisation half of the skip; deleting it would leave the job correct and
            // slower, because the constraint would catch every duplicate anyway.
            verify(reminders).findFeeIdsRemindedOn(RUN_DATE, List.of(1L, 2L, 3L));
        }
    }

    @Nested
    @DisplayName("counting a run")
    class Counting {

        @Test
        @DisplayName("a repeat of the same day sends nothing and reports every invoice as skipped")
        void aRepeatSkipsEverything() {
            when(fees.findDueForReminder(any())).thenReturn(List.of(fee(1L), fee(2L), fee(3L)));
            when(reminders.findFeeIdsRemindedOn(eq(RUN_DATE), any())).thenReturn(List.of(1L, 2L, 3L));

            FeeReminderRunResponse response = service.run(RUN_DATE);

            // This is the shape of the second run of a night, and the number the admin
            // route tells operators to read: three examined, none sent, three skipped. A
            // run that found nothing to do would say zero examined instead.
            assertThat(response.invoicesExamined()).isEqualTo(3);
            assertThat(response.sent()).isZero();
            assertThat(response.skipped()).isEqualTo(3);
            assertThat(response.failed()).isZero();
            verifyNoInteractions(dispatch);
        }

        @Test
        @DisplayName("an invoice reminded earlier today is skipped without being dispatched")
        void anAlreadyRemindedInvoiceIsNotDispatched() {
            when(fees.findDueForReminder(any())).thenReturn(List.of(fee(1L), fee(2L), fee(3L)));
            when(reminders.findFeeIdsRemindedOn(eq(RUN_DATE), any())).thenReturn(List.of(2L));
            when(dispatch.sendFor(1L, RUN_DATE)).thenReturn(FeeReminderDispatch.Outcome.SENT);
            when(dispatch.sendFor(3L, RUN_DATE)).thenReturn(FeeReminderDispatch.Outcome.SENT);

            FeeReminderRunResponse response = service.run(RUN_DATE);

            assertThat(response.invoicesExamined()).isEqualTo(3);
            assertThat(response.sent()).isEqualTo(2);
            assertThat(response.skipped()).isEqualTo(1);
            verify(dispatch, never()).sendFor(2L, RUN_DATE);
        }

        @Test
        @DisplayName("each outcome lands in its own counter")
        void eachOutcomeIsCountedSeparately() {
            when(fees.findDueForReminder(any())).thenReturn(List.of(fee(1L), fee(2L), fee(3L)));
            when(reminders.findFeeIdsRemindedOn(eq(RUN_DATE), any())).thenReturn(List.of());
            when(dispatch.sendFor(1L, RUN_DATE)).thenReturn(FeeReminderDispatch.Outcome.SENT);
            when(dispatch.sendFor(2L, RUN_DATE)).thenReturn(FeeReminderDispatch.Outcome.SKIPPED);
            when(dispatch.sendFor(3L, RUN_DATE)).thenReturn(FeeReminderDispatch.Outcome.FAILED);

            FeeReminderRunResponse response = service.run(RUN_DATE);

            // A SKIPPED from the dispatcher -- the invoice was paid between the query and
            // the send, or another instance won the race at the constraint -- is counted the
            // same way as one the cheap query caught. From outside they are the same event.
            assertThat(response.invoicesExamined()).isEqualTo(3);
            assertThat(response.sent()).isEqualTo(1);
            assertThat(response.skipped()).isEqualTo(1);
            assertThat(response.failed()).isEqualTo(1);
        }

        @Test
        @DisplayName("counts sent reminders for a day off the reminder table, for the day asked about")
        void countsSentForTheDayAskedAbout() {
            when(reminders.countByReminderDate(RUN_DATE)).thenReturn(17L);

            assertThat(service.countSentOn(RUN_DATE)).isEqualTo(17L);
        }
    }

    @Nested
    @DisplayName("isolating failures")
    class IsolatingFailures {

        @Test
        @DisplayName("one invoice throwing costs one invoice, not the night")
        void oneInvoiceThrowingCostsOneInvoice() {
            when(fees.findDueForReminder(any())).thenReturn(List.of(fee(1L), fee(2L), fee(3L)));
            when(reminders.findFeeIdsRemindedOn(eq(RUN_DATE), any())).thenReturn(List.of());
            when(dispatch.sendFor(1L, RUN_DATE)).thenReturn(FeeReminderDispatch.Outcome.SENT);
            when(dispatch.sendFor(2L, RUN_DATE)).thenThrow(new IllegalStateException("nobody anticipated this"));
            when(dispatch.sendFor(3L, RUN_DATE)).thenReturn(FeeReminderDispatch.Outcome.SENT);

            FeeReminderRunResponse response = service.run(RUN_DATE);

            // The whole reason the run has no transaction of its own. The loop presses on,
            // the failure is one number, and the invoice id is in the log -- which is what
            // turns a `failed` count into something somebody can act on.
            assertThat(response.sent()).isEqualTo(2);
            assertThat(response.failed()).isEqualTo(1);
            assertThat(response.skipped()).isZero();
            verify(dispatch).sendFor(3L, RUN_DATE);
        }

        @Test
        @DisplayName("an Error is not caught: a dying JVM is not a per-invoice problem")
        void anErrorIsNotCaught() {
            when(fees.findDueForReminder(any())).thenReturn(List.of(fee(1L), fee(2L)));
            when(reminders.findFeeIdsRemindedOn(eq(RUN_DATE), any())).thenReturn(List.of());
            when(dispatch.sendFor(1L, RUN_DATE)).thenThrow(new OutOfMemoryError("simulated"));

            // RuntimeException is caught, Error deliberately is not. Catching Error here
            // would have the loop press on through a dying JVM reporting per-invoice
            // failures, and the run would look like a bad address list rather than a host
            // that needs restarting.
            assertThatThrownBy(() -> service.run(RUN_DATE)).isInstanceOf(OutOfMemoryError.class);
            verify(dispatch, never()).sendFor(2L, RUN_DATE);
        }
    }

    @Nested
    @DisplayName("the transaction shape")
    class StructuralClaims {

        /**
         * These three assertions are the design, and nothing else in a unit test can see
         * them. A mock cannot observe a transaction boundary, so the alternative to
         * asserting the annotations is asserting nothing -- and the failure mode is one
         * already-reminded invoice aborting the entire night's run.
         */
        @Test
        @DisplayName("run carries no transaction, so a constraint violation cannot poison one")
        void runIsNotTransactional() throws Exception {
            Method run = FeeReminderService.class.getMethod("run", LocalDate.class);

            assertThat(run.getAnnotation(Transactional.class)).isNull();
            assertThat(FeeReminderService.class.getAnnotation(Transactional.class)).isNull();
        }

        @Test
        @DisplayName("each reminder is its own transaction, in a separate bean so the proxy is crossed")
        void eachReminderIsItsOwnTransaction() throws Exception {
            Method sendFor =
                    FeeReminderDispatch.class.getDeclaredMethod("sendFor", Long.class, LocalDate.class);
            Transactional annotation = sendFor.getAnnotation(Transactional.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
            // The separate bean is not stylistic. Spring's transaction advice lives on a
            // proxy, so a REQUIRES_NEW method called from another method of the same class
            // would run in the caller's transaction with the annotation silently ignored --
            // and every reminder would once again share one poisonable transaction.
            assertThat(sendFor.getDeclaringClass()).isNotEqualTo(FeeReminderService.class);
        }
    }

    private static HostelFee fee(long id) {
        HostelFee fee = new HostelFee();
        fee.setId(id);
        fee.setAmountPaise(50_000L);
        fee.setDueDate(RUN_DATE.plusDays(3));
        return fee;
    }
}
