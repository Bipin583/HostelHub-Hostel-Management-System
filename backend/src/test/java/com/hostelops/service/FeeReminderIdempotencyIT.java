package com.hostelops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelScope;
import com.hostelops.dto.fee.FeeReminderRunResponse;
import com.hostelops.support.AbstractPostgresIT;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The proof that the fee-reminder job may be run twice without reminding anybody twice.
 *
 * <p>{@code FeeReminderService} and {@code AdminJobController} both cite this class by name for
 * that claim, and the unit tests cannot settle it: the guarantee is
 * {@code uq_fee_reminders_per_day}, and a mocked repository has no unique constraints. What is
 * under test here is the constraint, the {@code REQUIRES_NEW} boundary that keeps a rejected
 * insert from poisoning the batch, and the counters the endpoint reports about both.
 *
 * <h2>Why the assertions are per invoice and not per run</h2>
 *
 * <p>{@code HostelFeeRepository.findDueForReminder} is global and unscoped -- a job has no
 * hostel of its own -- and this suite shares one database with every other {@code *IT}. So
 * {@code invoicesExamined} and {@code sent} include whatever else has been seeded, and asserting
 * either against a literal would make this class fail when an unrelated test adds an invoice.
 *
 * <p>Exactness comes from {@code reminderCountFor(feeId, date)} instead, which is scoped to a
 * row this test created and is therefore true no matter what the database holds. The two run
 * counters that <em>are</em> safe to assert are the ones on a repeat run at the same date:
 * {@code sent == 0} and {@code skipped == invoicesExamined}, which hold globally because by then
 * every invoice in scope has a reminder for that date, whoever put it there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeeReminderIdempotencyIT extends AbstractPostgresIT {

    private static final String FEE_REMINDERS = "/api/v1/admin/jobs/fee-reminders";

    /**
     * Mirrors {@code FeeReminderService.REMINDER_LEAD_DAYS}, which is private.
     *
     * <p>Duplicated rather than exposed: a lead time the tests can read is a lead time the tests
     * can be written around, and the boundary cases below are meant to pin the number down from
     * outside. If the service's value changes, {@link #anInvoiceDueBeyondTheLeadTimeIsNotReminded}
     * fails -- which is the intended way to find out.
     */
    private static final int REMINDER_LEAD_DAYS = 7;

    /** Rs. 25,000, the shape of a real hostel invoice. Nothing depends on the figure. */
    private static final long AMOUNT_PAISE = 25_000_00L;

    /**
     * A fixed run date, so a fixture is not written against whatever today happens to be.
     *
     * <p>Every test in this class uses it, which is safe because the guarantee is keyed on
     * {@code (fee_id, reminder_date)} and each test seeds its own invoices. Two tests sharing the
     * date interact only through the global counters, which is exactly why those are not
     * asserted -- see the class header.
     */
    private static final LocalDate RUN_DATE = LocalDate.of(2026, 5, 11);

    @Autowired
    private TestRestTemplate rest;

    // ------------------------------------------------------------------
    // The headline case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("running the job twice for the same day reminds each invoice exactly once")
    void aSecondRunForTheSameDaySendsNothing() {
        String token = accessTokenForAdmin(seedAdmin());
        SeededFee fee = seedFee(seedStudent(Gender.F, 2).studentId(), RUN_DATE.plusDays(1), AMOUNT_PAISE);

        FeeReminderRunResponse first = run(token, RUN_DATE);

        // 1. The job looked at what the query says it should have looked at. If these
        //    disagree, the run is not examining the set it reports on, and every other
        //    number it returns is describing something else.
        assertThat(first.invoicesExamined())
                .as("invoices examined against the same predicate asked in SQL")
                .isEqualTo((int) feesDueForReminderAt(RUN_DATE, REMINDER_LEAD_DAYS));
        assertThat(first.reminderDate()).isEqualTo(RUN_DATE);
        assertThat(first.failed()).isZero();

        // 2. The invariant, and the only one that can be exact in a shared database.
        assertThat(reminderCountFor(fee.feeId(), RUN_DATE))
                .as("reminders recorded against invoice %d on %s", fee.feeId(), RUN_DATE)
                .isEqualTo(1);

        FeeReminderRunResponse second = run(token, RUN_DATE);

        // 3. Still one. This is the whole claim: the second run sent nothing.
        assertThat(reminderCountFor(fee.feeId(), RUN_DATE)).isEqualTo(1);

        // 4. And the run said so. Every invoice in scope was skipped and none was sent --
        //    safe to assert globally, because by now every invoice due at this date has a
        //    reminder for it. A run that found nothing to do would report the same `sent`
        //    and a zero `invoicesExamined`, which is why `skipped` is the number to read.
        assertThat(second.sent()).as("nothing is sent twice").isZero();
        assertThat(second.failed()).as("a refused duplicate is a skip, not a failure").isZero();
        assertThat(second.skipped()).isEqualTo(second.invoicesExamined());
        assertThat(second.invoicesExamined()).isPositive();
    }

    @Test
    @DisplayName("four simultaneous runs of the job still leave one reminder per invoice")
    void concurrentRunsCannotRemindTheSameInvoiceTwice() throws Exception {
        int runners = 4;
        String token = accessTokenForAdmin(seedAdmin());

        List<SeededFee> fees = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            fees.add(seedFee(seedStudent(Gender.M, 3).studentId(), RUN_DATE, AMOUNT_PAISE));
        }

        List<ResponseEntity<String>> responses =
                simultaneously(runners, index -> postRun(token, RUN_DATE));

        // The cheap findFeeIdsRemindedOn skip cannot help here: all four runs read the
        // table before any of them has written to it, so every one of them believes it
        // should send every reminder. What stops the duplicates is the constraint alone,
        // which is the half of the guarantee that actually carries it.
        for (SeededFee fee : fees) {
            assertThat(reminderCountFor(fee.feeId(), RUN_DATE))
                    .as("reminders for invoice %d after %d concurrent runs", fee.feeId(), runners)
                    .isEqualTo(1);
        }

        // Losing the race is not an error for the job either: the duplicate insert is
        // caught per invoice inside its own REQUIRES_NEW transaction, so every run
        // returns 200 and reports its losses as skips rather than dying on them.
        assertThat(statusCount(responses, HttpStatus.OK)).isEqualTo(runners);
        assertThat(responses).noneMatch(response -> response.getStatusCode().is5xxServerError());
        for (ResponseEntity<String> response : responses) {
            assertThat(bodyAs(response, FeeReminderRunResponse.class).failed())
                    .as("a concurrent duplicate is counted as skipped, never as failed")
                    .isZero();
        }
    }

    @Test
    @DisplayName("the guarantee is one reminder per day, not one reminder ever")
    void thenextDayRemindsTheSameInvoiceAgain() {
        String token = accessTokenForAdmin(seedAdmin());
        // Due today, so it is overdue tomorrow and still in scope: an unpaid invoice is
        // reminded every day from a week before it is due until it is paid or written off.
        SeededFee fee = seedFee(seedStudent(Gender.F, 1).studentId(), RUN_DATE, AMOUNT_PAISE);

        run(token, RUN_DATE);
        run(token, RUN_DATE.plusDays(1));

        assertThat(reminderCountFor(fee.feeId(), RUN_DATE)).isEqualTo(1);
        assertThat(reminderCountFor(fee.feeId(), RUN_DATE.plusDays(1))).isEqualTo(1);
        assertThat(reminderCountFor(fee.feeId()))
                .as("two days of reminders, one row each")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // What is in scope
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a reminder left by last night's run is not repeated by this one")
    void anInvoiceAlreadyRemindedTodayIsSkipped() {
        String token = accessTokenForAdmin(seedAdmin());
        SeededFee fee = seedFee(seedStudent(Gender.F, 2).studentId(), RUN_DATE, AMOUNT_PAISE);

        // Written directly, as a previous run that died after its inserts would have left
        // it. The job's next decision is then measured against a fixture rather than
        // against its own earlier output.
        seedFeeReminder(fee.feeId(), RUN_DATE);

        run(token, RUN_DATE);

        assertThat(reminderCountFor(fee.feeId(), RUN_DATE)).isEqualTo(1);
    }

    @Test
    @DisplayName("settled and cancelled invoices are never reminded")
    void aSettledInvoiceIsNotReminded() {
        String token = accessTokenForAdmin(seedAdmin());
        long studentId = seedStudent(Gender.M, 4).studentId();

        SeededFee paid = seedFee(studentId, RUN_DATE, AMOUNT_PAISE);
        markFeePaid(paid.feeId());
        SeededFee cancelled = seedFee(studentId, RUN_DATE, AMOUNT_PAISE);
        cancelFee(cancelled.feeId());

        run(token, RUN_DATE);

        assertThat(reminderCountFor(paid.feeId())).as("a paid invoice").isZero();
        assertThat(reminderCountFor(cancelled.feeId())).as("a written-off invoice").isZero();
    }

    @Test
    @DisplayName("a part-paid invoice is still chased for the balance")
    void aPartlyPaidInvoiceIsReminded() {
        String token = accessTokenForAdmin(seedAdmin());
        SeededFee fee = seedFee(
                seedStudent(Gender.F, 3).studentId(), RUN_DATE, AMOUNT_PAISE, AMOUNT_PAISE / 4);

        run(token, RUN_DATE);

        assertThat(reminderCountFor(fee.feeId(), RUN_DATE)).isEqualTo(1);
    }

    @Test
    @DisplayName("reminding starts exactly a week before the due date and not a day earlier")
    void anInvoiceDueBeyondTheLeadTimeIsNotReminded() {
        String token = accessTokenForAdmin(seedAdmin());
        long studentId = seedStudent(Gender.M, 2).studentId();

        SeededFee onTheBoundary =
                seedFee(studentId, RUN_DATE.plusDays(REMINDER_LEAD_DAYS), AMOUNT_PAISE);
        SeededFee justOutside =
                seedFee(studentId, RUN_DATE.plusDays(REMINDER_LEAD_DAYS + 1), AMOUNT_PAISE);

        run(token, RUN_DATE);

        // Both halves matter. Without the first, the lead time could be shorter than
        // documented and nothing would notice; without the second, it could be unbounded
        // and every future invoice on the books would be reminded nightly.
        assertThat(reminderCountFor(onTheBoundary.feeId(), RUN_DATE))
                .as("due in exactly %d days", REMINDER_LEAD_DAYS)
                .isEqualTo(1);
        assertThat(reminderCountFor(justOutside.feeId()))
                .as("due in %d days, which is not yet its business", REMINDER_LEAD_DAYS + 1)
                .isZero();
    }

    // ------------------------------------------------------------------
    // Defence in depth, and the route itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the database refuses a second reminder for the same invoice and day")
    void theUniqueConstraintCatchesWritesThatBypassTheJob() {
        SeededFee fee = seedFee(seedStudent(Gender.F, 1).studentId(), RUN_DATE, AMOUNT_PAISE);
        seedFeeReminder(fee.feeId(), RUN_DATE);

        // A second job instance, a hand-run script, a replayed migration: the guarantee has
        // to hold for writes that never went through FeeReminderDispatch, because the
        // application layer is not what is enforcing it.
        assertThatThrownBy(() -> seedFeeReminder(fee.feeId(), RUN_DATE))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_fee_reminders_per_day");

        assertThat(reminderCountFor(fee.feeId(), RUN_DATE)).isEqualTo(1);
    }

    @Test
    @DisplayName("a warden cannot trigger a campus-wide job")
    void theJobRouteIsAdminOnly() {
        SeededUser warden = seedWarden(HostelScope.LH);
        SeededFee fee = seedFee(seedStudent(Gender.F, 2).studentId(), RUN_DATE, AMOUNT_PAISE);

        ResponseEntity<String> response = postRun(accessTokenFor(warden, HostelScope.LH), RUN_DATE);

        // The route prefix is the only thing standing between a warden and a run that
        // reminds students in hostels they cannot otherwise see -- FeeReminderService
        // applies no AccessScope at all, deliberately, because a job has no hostel.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("FORBIDDEN");
        assertThat(reminderCountFor(fee.feeId())).as("and nothing ran").isZero();
    }

    // ------------------------------------------------------------------
    // harness
    // ------------------------------------------------------------------

    private FeeReminderRunResponse run(String token, LocalDate date) {
        ResponseEntity<String> response = postRun(token, date);
        assertThat(response.getStatusCode())
                .as("the run itself must succeed; failures are counted, not thrown")
                .isEqualTo(HttpStatus.OK);
        return bodyAs(response, FeeReminderRunResponse.class);
    }

    private ResponseEntity<String> postRun(String token, LocalDate date) {
        return rest.exchange(FEE_REMINDERS + "?date=" + date, HttpMethod.POST,
                new HttpEntity<>(jsonWithToken(token)), String.class);
    }
}
