package com.hostelops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.PaymentStatus;
import com.hostelops.dto.fee.FeePaymentResponse;
import com.hostelops.dto.fee.PaymentInitiationResponse;
import com.hostelops.payment.MockPaymentGateway;
import com.hostelops.support.AbstractPostgresIT;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The proof that a redelivered payment callback credits an invoice once.
 *
 * <p>{@code PaymentService} and {@code MockPaymentGateway} both cite this class by name. Every
 * gateway redelivers callbacks -- on a timeout, on a non-2xx, on an operator's replay from the
 * dashboard -- so "the same callback arrives twice" is the normal case, not the exotic one, and
 * the two things standing against it are a status guard and a row lock. Neither can be proven
 * with a mocked repository: a mock has no READ COMMITTED snapshot and no {@code FOR UPDATE}.
 *
 * <h2>The signature is real here</h2>
 *
 * <p>Callbacks are signed with {@link MockPaymentGateway#signatureFor}, an HMAC over the same two
 * references the adapter verifies, using the same published development secret. So the
 * verification branch under test is the one production takes -- there is no test-only bypass,
 * which is the point of the mock holding a real secret rather than a flag that skips the check.
 *
 * <h2>Why the racing fixture pays in full</h2>
 *
 * <p>{@link #concurrentRedeliveriesCreditTheInvoiceOnce} races a payment for the invoice's whole
 * balance. That is deliberate and it is narrower than it looks: what makes the full-amount case
 * safe is partly {@code HostelFee.applyPayment} refusing to overshoot, and a <em>partial</em>
 * payment redelivered concurrently is not protected by that -- see the note on that method. The
 * partial path is therefore proven sequentially, below, and the concurrent partial case is a
 * known finding rather than a test asserted to pass. A committed test that is expected to fail
 * teaches the suite to be ignored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentCallbackIT extends AbstractPostgresIT {

    private static final String PAYMENTS = "/api/v1/student/payments";
    private static final String CALLBACK = PAYMENTS + "/callback";

    /** Rs. 45,000, and evenly halvable so a part payment is exactly half an invoice. */
    private static final long FEE_AMOUNT_PAISE = 45_000_00L;
    private static final long HALF_PAISE = FEE_AMOUNT_PAISE / 2;

    private static final LocalDate DUE_DATE = LocalDate.of(2026, 6, 30);

    @Autowired
    private TestRestTemplate rest;

    /**
     * The adapter, injected so the test can sign as the provider would.
     *
     * <p>Named concretely rather than taken from the registry: signing is a capability of this
     * one adapter and is absent from the {@code PaymentGateway} interface, so asking for it by
     * type is what makes "only the mock can sign" visible at the call site.
     */
    @Autowired
    private MockPaymentGateway mockGateway;

    // ------------------------------------------------------------------
    // The headline case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("six simultaneous redeliveries of one callback credit the invoice once")
    void concurrentRedeliveriesCreditTheInvoiceOnce() throws Exception {
        int callbacks = 6;

        SeededStudent student = seedStudent(Gender.F, 2);
        String token = accessTokenForStudent(student);
        SeededFee fee = seedFee(student.studentId(), DUE_DATE, FEE_AMOUNT_PAISE);

        PaymentInitiationResponse opened =
                initiate(token, fee.feeId(), FEE_AMOUNT_PAISE, idempotencyKey());
        String paymentRef = paymentReference();
        String signature = mockGateway.signatureFor(opened.providerOrderId(), paymentRef);

        List<ResponseEntity<String>> responses = simultaneously(callbacks,
                index -> postCallback(token, opened.providerOrderId(), paymentRef, signature));

        // 1. The money is the invariant. A second credit would take amount_paid_paise past
        //    the invoice total -- the lost-update race from the allocation code, in a
        //    different table: two callbacks read the old balance, both add to it, and the
        //    later write erases the earlier one. What prevents it is findByIdForUpdate.
        assertThat(amountPaidPaiseOf(fee.feeId()))
                .as("paise credited to invoice %d after %d simultaneous callbacks",
                        fee.feeId(), callbacks)
                .isEqualTo(FEE_AMOUNT_PAISE);
        assertThat(feeStatusOf(fee.feeId())).isEqualTo("PAID");

        // 2. And one attempt settled, not several. The attempt row is the audit record of
        //    a charge; two SUCCEEDED rows for one charge is a reconciliation problem even
        //    if the balance happens to come out right.
        assertThat(paymentCountForFee(fee.feeId(), PaymentStatus.SUCCEEDED)).isEqualTo(1);
        assertThat(paymentCountForFee(fee.feeId())).as("no extra attempts were invented").isEqualTo(1);

        // 3. Exactly one caller was told it succeeded. A gateway that gets two 200s for one
        //    payment has been told, twice, that the hostel accepted it.
        assertThat(statusCount(responses, HttpStatus.OK)).isEqualTo(1);
        assertThat(statusCount(responses, HttpStatus.CONFLICT)).isEqualTo(callbacks - 1);

        // 4. Losing a redelivery race is not a server error, and the loser is told which
        //    of the two conflicts it hit: its own attempt was already settled, or the
        //    invoice was settled underneath it. Neither answer leaks a constraint name.
        assertThat(responses).noneMatch(response -> response.getStatusCode().is5xxServerError());
        responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .forEach(response -> {
                    assertThat(errorCodeOf(response))
                            .isIn("PAYMENT_ALREADY_SETTLED", "FEE_ALREADY_SETTLED");
                    assertThat(response.getBody())
                            .doesNotContain("uq_", "ck_", "SQLState", "org.postgresql");
                });
    }

    @Test
    @DisplayName("a verified callback credits the invoice and settles the attempt")
    void aVerifiedCallbackCreditsTheInvoice() {
        SeededStudent student = seedStudent(Gender.M, 1);
        String token = accessTokenForStudent(student);
        SeededFee fee = seedFee(student.studentId(), DUE_DATE, FEE_AMOUNT_PAISE);

        PaymentInitiationResponse opened =
                initiate(token, fee.feeId(), FEE_AMOUNT_PAISE, idempotencyKey());

        // 1. Opening a checkout charges nothing and credits nothing. The attempt exists so
        //    that a callback has a row to name, and the amount comes off that row rather
        //    than out of the callback body.
        assertThat(opened.provider()).isEqualTo(MockPaymentGateway.NAME);
        assertThat(opened.alreadyInitiated()).isFalse();
        assertThat(opened.providerOrderId()).startsWith("mock_order_");
        assertThat(paymentStatusOf(opened.paymentId())).isEqualTo("PENDING");
        assertThat(providerPaymentIdOf(opened.paymentId())).isNull();
        assertThat(amountPaidPaiseOf(fee.feeId())).isZero();

        String paymentRef = paymentReference();
        ResponseEntity<String> response = confirm(token, opened.providerOrderId(), paymentRef);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        FeePaymentResponse settled = bodyAs(response, FeePaymentResponse.class);

        // 2. The outcome came from verifying the signature, not from the request saying so.
        assertThat(settled.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(settled.providerPaymentId()).isEqualTo(paymentRef);
        assertThat(settled.completedAt()).isNotNull();
        assertThat(settled.amountPaise()).isEqualTo(FEE_AMOUNT_PAISE);

        // 3. And the invoice agrees with it.
        assertThat(feeStatusOf(fee.feeId())).isEqualTo("PAID");
        assertThat(amountPaidPaiseOf(fee.feeId())).isEqualTo(FEE_AMOUNT_PAISE);
        assertThat(paymentCountForFee(fee.feeId(), PaymentStatus.SUCCEEDED)).isEqualTo(1);
    }

    @Test
    @DisplayName("the same callback delivered again is refused, and nothing moves")
    void aRedeliveredCallbackIsRefused() {
        SeededStudent student = seedStudent(Gender.F, 3);
        String token = accessTokenForStudent(student);
        SeededFee fee = seedFee(student.studentId(), DUE_DATE, FEE_AMOUNT_PAISE);

        PaymentInitiationResponse opened =
                initiate(token, fee.feeId(), FEE_AMOUNT_PAISE, idempotencyKey());
        String paymentRef = paymentReference();

        assertThat(confirm(token, opened.providerOrderId(), paymentRef).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> again = confirm(token, opened.providerOrderId(), paymentRef);

        // A 409 rather than a quiet 200. The redelivery is not the caller's fault, but a
        // silent success would leave the gateway unable to tell "credited" from "credited
        // twice and one of them ignored" -- and this is the endpoint where that matters.
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCodeOf(again)).isEqualTo("PAYMENT_ALREADY_SETTLED");
        assertThat(amountPaidPaiseOf(fee.feeId())).isEqualTo(FEE_AMOUNT_PAISE);
        assertThat(paymentCountForFee(fee.feeId(), PaymentStatus.SUCCEEDED)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // What the callback is not allowed to do
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unsigned or wrongly-signed callback cannot settle anything")
    void anUnverifiableCallbackIsRejected() {
        SeededStudent student = seedStudent(Gender.M, 2);
        String token = accessTokenForStudent(student);
        SeededFee fee = seedFee(student.studentId(), DUE_DATE, FEE_AMOUNT_PAISE);

        PaymentInitiationResponse opened =
                initiate(token, fee.feeId(), FEE_AMOUNT_PAISE, idempotencyKey());
        String paymentRef = paymentReference();

        ResponseEntity<String> forged =
                postCallback(token, opened.providerOrderId(), paymentRef, "not-a-signature");

        assertThat(forged.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(forged)).isEqualTo("PAYMENT_VERIFICATION_FAILED");

        // A signature that is genuinely ours but computed over a different payment
        // reference. This is the interesting half: it proves the HMAC is bound to the
        // pair being claimed, not merely well-formed, so a signature captured from one
        // callback cannot be pasted onto another.
        String signatureForSomethingElse =
                mockGateway.signatureFor(opened.providerOrderId(), paymentReference());
        ResponseEntity<String> transplanted = postCallback(
                token, opened.providerOrderId(), paymentRef, signatureForSomethingElse);

        assertThat(transplanted.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(transplanted)).isEqualTo("PAYMENT_VERIFICATION_FAILED");

        // Nothing was revealed and nothing was written: an unauthentic caller cannot even
        // tell whether the attempt it named is settled.
        assertThat(paymentStatusOf(opened.paymentId())).isEqualTo("PENDING");
        assertThat(providerPaymentIdOf(opened.paymentId())).isNull();
        assertThat(amountPaidPaiseOf(fee.feeId())).isZero();
        assertThat(feeStatusOf(fee.feeId())).isNotEqualTo("PAID");
    }

    @Test
    @DisplayName("a correctly signed callback for an order nobody opened is a 404")
    void aCallbackForAnUnknownOrderIsNotFound() {
        SeededStudent student = seedStudent(Gender.F, 1);
        String token = accessTokenForStudent(student);

        // Signed properly, so the 404 is about the order not existing rather than about
        // the signature -- the reference is checked before verification, because the
        // stored row is what says which adapter's secret to verify against.
        String orderId = "mock_order_" + nextSequence() + "deadbeef";
        ResponseEntity<String> response = confirm(token, orderId, paymentReference());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCodeOf(response)).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("a warden cannot post a payment callback")
    void theCallbackIsNotAPublicWebhook() {
        SeededStudent student = seedStudent(Gender.F, 4);
        String studentToken = accessTokenForStudent(student);
        SeededFee fee = seedFee(student.studentId(), DUE_DATE, FEE_AMOUNT_PAISE);
        PaymentInitiationResponse opened =
                initiate(studentToken, fee.feeId(), FEE_AMOUNT_PAISE, idempotencyKey());

        SeededUser warden = seedWarden(HostelScope.LH);
        String paymentRef = paymentReference();
        ResponseEntity<String> response = postCallback(
                accessTokenFor(warden, HostelScope.LH), opened.providerOrderId(), paymentRef,
                mockGateway.signatureFor(opened.providerOrderId(), paymentRef));

        // The route sits under /api/v1/student/**, so it needs a logged-in payer. The
        // signature is the authorization for moving money, but authentication is still
        // required to reach the code that checks it -- an unauthenticated endpoint would
        // be an oracle for probing order references.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("FORBIDDEN");
        assertThat(paymentStatusOf(opened.paymentId())).isEqualTo("PENDING");
        assertThat(amountPaidPaiseOf(fee.feeId())).isZero();
    }

    @Test
    @DisplayName("a student cannot open a checkout against somebody else's invoice")
    void anotherStudentsInvoiceIsNotFound() {
        SeededStudent payer = seedStudent(Gender.M, 3);
        SeededStudent stranger = seedStudent(Gender.M, 3);
        SeededFee theirFee = seedFee(stranger.studentId(), DUE_DATE, FEE_AMOUNT_PAISE);

        ResponseEntity<String> response = postInitiation(
                accessTokenForStudent(payer), theirFee.feeId(), HALF_PAISE, idempotencyKey());

        // 404 rather than 403: the invoice is loaded by (id, studentId), so the answer to
        // "is there such a fee" and "is it mine" is one query and one response. A 403 here
        // would confirm the id exists, which is how an id space gets enumerated.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCodeOf(response)).isEqualTo("NOT_FOUND");
        assertThat(paymentCountForFee(theirFee.feeId())).isZero();
    }

    // ------------------------------------------------------------------
    // The idempotency key
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one idempotency key opens one attempt, however many times it is sent")
    void oneKeyOpensOneAttempt() {
        SeededStudent student = seedStudent(Gender.F, 2);
        String token = accessTokenForStudent(student);
        SeededFee fee = seedFee(student.studentId(), DUE_DATE, FEE_AMOUNT_PAISE);
        String key = idempotencyKey();

        PaymentInitiationResponse first = initiate(token, fee.feeId(), HALF_PAISE, key);
        PaymentInitiationResponse retry = initiate(token, fee.feeId(), HALF_PAISE, key);

        // The ordinary retry: a client that lost the response, or a browser that resent
        // the form. It gets the attempt it already has, so the same checkout resumes
        // rather than a second charge opening beside it.
        assertThat(retry.alreadyInitiated()).as("the client is told this is its own attempt").isTrue();
        assertThat(retry.paymentId()).isEqualTo(first.paymentId());
        assertThat(retry.providerOrderId()).isEqualTo(first.providerOrderId());
        assertThat(retry.amountPaise()).isEqualTo(first.amountPaise());
        assertThat(paymentCountForFee(fee.feeId())).isEqualTo(1);
    }

    @Test
    @DisplayName("five simultaneous requests carrying one key still open one attempt")
    void concurrentRequestsWithOneKeyOpenOneAttempt() throws Exception {
        int attempts = 5;

        SeededStudent student = seedStudent(Gender.M, 4);
        String token = accessTokenForStudent(student);
        SeededFee fee = seedFee(student.studentId(), DUE_DATE, FEE_AMOUNT_PAISE);
        String key = idempotencyKey();

        List<ResponseEntity<String>> responses = simultaneously(attempts,
                index -> postInitiation(token, fee.feeId(), HALF_PAISE, key));

        // The lookup cannot carry this: every request reads the table before any of them
        // has committed, so all five believe the key is free. uq_fee_payments_idempotency
        // is what makes the second insert impossible -- the check is a convenience, not
        // the guarantee, which is why it is written in that order in the service.
        assertThat(paymentCountForFee(fee.feeId()))
                .as("attempts opened for invoice %d by %d concurrent requests with one key",
                        fee.feeId(), attempts)
                .isEqualTo(1);

        assertThat(responses).noneMatch(response -> response.getStatusCode().is5xxServerError());
        for (ResponseEntity<String> response : responses) {
            if (response.getStatusCode() == HttpStatus.CREATED) {
                // Whoever is handed a 201 must be handed something usable: a committed
                // PENDING row always carries a provider order id, because the order is
                // opened inside the transaction that claimed the key.
                assertThat(bodyAs(response, PaymentInitiationResponse.class).providerOrderId())
                        .isNotBlank();
            } else {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(errorCodeOf(response)).isEqualTo("DUPLICATE_RESOURCE");
            }
        }
    }

    @Test
    @DisplayName("a settled attempt's key cannot be reused to open another checkout")
    void aSettledKeyCannotBeReplayed() {
        SeededStudent student = seedStudent(Gender.F, 1);
        String token = accessTokenForStudent(student);
        SeededFee fee = seedFee(student.studentId(), DUE_DATE, FEE_AMOUNT_PAISE);
        String key = idempotencyKey();

        PaymentInitiationResponse opened = initiate(token, fee.feeId(), HALF_PAISE, key);
        assertThat(confirm(token, opened.providerOrderId(), paymentReference()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> reused = postInitiation(token, fee.feeId(), HALF_PAISE, key);

        // Replaying a paid attempt would hand back an order reference the client is about
        // to open checkout with, which is an invitation to pay the same half twice. The
        // balance is still owed and can still be paid -- with a new key.
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCodeOf(reused)).isEqualTo("PAYMENT_ALREADY_SETTLED");
        assertThat(paymentCountForFee(fee.feeId())).isEqualTo(1);
        assertThat(amountPaidPaiseOf(fee.feeId())).isEqualTo(HALF_PAISE);
    }

    // ------------------------------------------------------------------
    // Amounts
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two part payments settle the invoice between them")
    void partPaymentsAddUp() {
        SeededStudent student = seedStudent(Gender.M, 1);
        String token = accessTokenForStudent(student);
        SeededFee fee = seedFee(student.studentId(), DUE_DATE, FEE_AMOUNT_PAISE);

        PaymentInitiationResponse firstHalf = initiate(token, fee.feeId(), HALF_PAISE, idempotencyKey());
        assertThat(confirm(token, firstHalf.providerOrderId(), paymentReference()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Half paid is not settled, and is still payable -- the state the reminder job
        // keeps chasing and the state a scholarship arriving late produces.
        assertThat(amountPaidPaiseOf(fee.feeId())).isEqualTo(HALF_PAISE);
        assertThat(feeStatusOf(fee.feeId())).isEqualTo("PARTIALLY_PAID");

        PaymentInitiationResponse secondHalf = initiate(token, fee.feeId(), HALF_PAISE, idempotencyKey());
        assertThat(confirm(token, secondHalf.providerOrderId(), paymentReference()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(amountPaidPaiseOf(fee.feeId())).isEqualTo(FEE_AMOUNT_PAISE);
        assertThat(feeStatusOf(fee.feeId())).isEqualTo("PAID");
        assertThat(paymentCountForFee(fee.feeId(), PaymentStatus.SUCCEEDED))
                .as("two charges, two attempt rows")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("more than the outstanding balance is refused before the gateway is contacted")
    void overpaymentIsRefusedUpFront() {
        SeededStudent student = seedStudent(Gender.F, 3);
        String token = accessTokenForStudent(student);
        SeededFee fee = seedFee(student.studentId(), DUE_DATE, FEE_AMOUNT_PAISE, HALF_PAISE);

        ResponseEntity<String> response = postInitiation(
                token, fee.feeId(), FEE_AMOUNT_PAISE, idempotencyKey());

        // Half is already paid, so asking to pay the full amount overshoots. Refused at
        // initiation rather than at the callback, because by callback time the money is
        // captured and the remedy is a refund instead of a 400.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(response)).isEqualTo("PAYMENT_AMOUNT_INVALID");
        assertThat(paymentCountForFee(fee.feeId()))
                .as("no attempt row survives a refused initiation")
                .isZero();
        assertThat(amountPaidPaiseOf(fee.feeId())).isEqualTo(HALF_PAISE);
    }

    @Test
    @DisplayName("a settled invoice cannot be paid again")
    void aSettledInvoiceRefusesNewPayments() {
        SeededStudent student = seedStudent(Gender.M, 2);
        String token = accessTokenForStudent(student);
        SeededFee fee = seedFee(student.studentId(), DUE_DATE, FEE_AMOUNT_PAISE);
        markFeePaid(fee.feeId());

        ResponseEntity<String> response =
                postInitiation(token, fee.feeId(), HALF_PAISE, idempotencyKey());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCodeOf(response)).isEqualTo("FEE_ALREADY_SETTLED");
        assertThat(paymentCountForFee(fee.feeId())).isZero();
    }

    @Test
    @DisplayName("the signing secret never leaves the server")
    void onlyThePublishableKeyIsSent() {
        SeededStudent student = seedStudent(Gender.F, 4);
        String token = accessTokenForStudent(student);
        SeededFee fee = seedFee(student.studentId(), DUE_DATE, FEE_AMOUNT_PAISE);

        ResponseEntity<String> response =
                postInitiation(token, fee.feeId(), HALF_PAISE, idempotencyKey());

        // The predecessor rendered both credentials into the payment template because the
        // vendor's quickstart snippet did, putting the signing secret in view-source. The
        // response carries the publishable identifier and nothing else.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bodyAs(response, PaymentInitiationResponse.class).publicKey())
                .isEqualTo(mockGateway.publicKey());
        assertThat(response.getBody()).doesNotContain(MockPaymentGateway.DEV_SECRET);
    }

    // ------------------------------------------------------------------
    // harness
    // ------------------------------------------------------------------

    private PaymentInitiationResponse initiate(String token, long feeId, long amountPaise, String key) {
        ResponseEntity<String> response = postInitiation(token, feeId, amountPaise, key);
        assertThat(response.getStatusCode())
                .as("opening a checkout: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return bodyAs(response, PaymentInitiationResponse.class);
    }

    private ResponseEntity<String> postInitiation(
            String token, long feeId, long amountPaise, String key) {
        HttpHeaders headers = jsonWithToken(token);
        headers.set("Idempotency-Key", key);
        String body = "{\"feeId\":%d,\"amountPaise\":%d}".formatted(feeId, amountPaise);
        return rest.exchange(PAYMENTS, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** Posts the callback a real gateway would post: correctly signed over both references. */
    private ResponseEntity<String> confirm(String token, String providerOrderId, String providerPaymentId) {
        return postCallback(token, providerOrderId, providerPaymentId,
                mockGateway.signatureFor(providerOrderId, providerPaymentId));
    }

    private ResponseEntity<String> postCallback(
            String token, String providerOrderId, String providerPaymentId, String signature) {
        String body = """
                {"providerOrderId":"%s","providerPaymentId":"%s","signature":"%s"}
                """.formatted(providerOrderId, providerPaymentId, signature);
        return rest.exchange(CALLBACK, HttpMethod.POST,
                new HttpEntity<>(body, jsonWithToken(token)), String.class);
    }

    /**
     * A payment reference in the provider's shape, unique across the shared database.
     *
     * <p>Unique because {@code uq_fee_payments_provider_ref} is a real index: a reference
     * reused from another test's settled payment would collide on it and the failure would
     * read as a bug in the code under test.
     */
    private String paymentReference() {
        return "mock_pay_" + nextSequence();
    }

    /** Likewise unique: {@code uq_fee_payments_idempotency} is global, not per student. */
    private String idempotencyKey() {
        return "it-key-" + nextSequence();
    }
}
