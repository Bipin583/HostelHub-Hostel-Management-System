package com.hostelops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hostelops.domain.FeePayment;
import com.hostelops.domain.FeeStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelFee;
import com.hostelops.domain.PaymentStatus;
import com.hostelops.domain.Role;
import com.hostelops.domain.Student;
import com.hostelops.dto.fee.FeePaymentResponse;
import com.hostelops.dto.fee.PaymentInitiationRequest;
import com.hostelops.dto.fee.PaymentInitiationResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.PaymentMapper;
import com.hostelops.payment.PaymentGateway;
import com.hostelops.payment.PaymentGatewayException;
import com.hostelops.payment.PaymentGatewayRegistry;
import com.hostelops.payment.PaymentOrderRequest;
import com.hostelops.repository.FeePaymentRepository;
import com.hostelops.repository.HostelFeeRepository;
import com.hostelops.security.AccessScope;
import com.hostelops.security.CurrentUserProvider;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;

/**
 * What {@link PaymentService} promises that is not a field assignment.
 *
 * <p>Two of this project's claims live in that class, and only one half of each can be
 * tested here. "A request must not charge twice" rests on
 * {@code uq_fee_payments_idempotency}; "two payments must not erase each other" rests on
 * a {@code SELECT ... FOR UPDATE}. Neither a unique index nor a row lock exists in a
 * process with no database, so {@code PaymentCallbackIT} is where those are proved.
 *
 * <p>What is provable with mocks is the part that would make the database's guarantees
 * unreachable if it were wrong -- the <em>order</em> of the calls, and which of them are
 * not made at all:
 *
 * <ul>
 *   <li>the idempotency key is claimed by a flush <em>before</em> the provider is
 *       contacted, so a duplicate loses the race at the constraint rather than at the
 *       provider, and a gateway outage unwinds a row that has no order behind it;
 *   <li>a callback is verified against the provider stored on the row, never the
 *       configured one -- asserted as {@code gateways.selected()} never being called;
 *   <li>the signature is checked before the payment's state is consulted, so an
 *       unauthentic caller cannot distinguish a settled attempt from an open one;
 *   <li>the invoice is read through {@code findByIdForUpdate} and never through
 *       {@code findById}, because the read <em>is</em> the lock -- a test that only
 *       asserted the balance would pass against unlocked code;
 *   <li>an invoice that cannot absorb the payment leaves the row {@code PENDING} and
 *       writes nothing, because the money is captured and {@code FAILED} would be a lie.
 * </ul>
 *
 * <p>{@link PaymentMapper} is real rather than mocked: it has no dependencies, and a
 * stubbed mapper would let a test assert a response the production one cannot build.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService")
class PaymentServiceTest {

    private static final long STUDENT_USER_ID = 41L;
    private static final long STUDENT_ID = 4L;
    private static final long OTHER_STUDENT_ID = 5L;
    private static final long ADMIN_USER_ID = 1L;
    private static final long FEE_ID = 900L;
    private static final long PAYMENT_ID = 7100L;

    private static final String KEY = "checkout-2f8c1d94";
    private static final String ORDER_ID = "mock_order_9f2c1a77b1e4d0aa";
    private static final String PROVIDER_PAYMENT_ID = "mock_pay_c41d90f2";
    private static final String SIGNATURE = "9d4f0c1b6a8e3f52";

    /**
     * Deliberately not the configured provider's name. Every {@code settle} fixture is
     * taken through an adapter the registry is <em>not</em> currently selecting, so a
     * verification that quietly used {@code gateways.selected()} would still be reading a
     * different string than the row says.
     */
    private static final String STORED_PROVIDER = "razorpay";

    @Mock private FeePaymentRepository payments;
    @Mock private HostelFeeRepository fees;
    @Mock private PaymentGatewayRegistry gateways;
    @Mock private PaymentGateway gateway;
    @Mock private CurrentUserProvider currentUser;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(payments, fees, gateways, new PaymentMapper(), currentUser);
    }

    @Nested
    @DisplayName("starting a payment")
    class Initiating {

        @Test
        @DisplayName("requires an Idempotency-Key and names the header when it is missing")
        void requiresAKey() {
            asStudent();

            for (String absent : new String[] {null, "", "   "}) {
                assertThatThrownBy(() -> service.initiate(request(10_000L), absent))
                        .isInstanceOf(ApiException.class)
                        .satisfies(thrown -> {
                            ApiException e = (ApiException) thrown;
                            assertThat(e.getCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                            assertThat(e.getDetails()).containsEntry("header", "Idempotency-Key");
                        });
            }

            // Nothing was looked up and nothing was written: the key is validated before
            // any state is touched, so a client that forgot the header cannot create rows.
            verifyNoInteractions(payments, fees, gateways);
        }

        @Test
        @DisplayName("refuses an over-long key as a 400 rather than letting the column truncate it")
        void refusesAnOverLongKey() {
            asStudent();
            String tooLong = "k".repeat(81);

            assertThatThrownBy(() -> service.initiate(request(10_000L), tooLong))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException e = (ApiException) thrown;
                        assertThat(e.getCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                        assertThat(e.getMessage()).contains("80");
                        assertThat(e.getDetails()).containsEntry("length", 81);
                    });
            verifyNoInteractions(payments);
        }

        @Test
        @DisplayName("strips the key before using it, so whitespace does not make a retry a new payment")
        void stripsTheKey() {
            asStudent();
            when(payments.findByIdempotencyKey(KEY)).thenReturn(Optional.of(pendingPayment(unpaidFee(50_000L))));
            when(gateways.forProvider(STORED_PROVIDER)).thenReturn(gateway);
            when(gateway.publicKey()).thenReturn("rzp_test_public");

            PaymentInitiationResponse response = service.initiate(request(50_000L), "  " + KEY + "\n");

            assertThat(response.alreadyInitiated()).isTrue();
            verify(payments).findByIdempotencyKey(KEY);
        }

        @Test
        @DisplayName("claims the key by flushing before the provider is contacted")
        void claimsTheKeyBeforeContactingTheProvider() {
            asStudent();
            HostelFee fee = unpaidFee(50_000L);
            when(payments.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
            when(fees.findByIdAndStudentId(FEE_ID, STUDENT_ID)).thenReturn(Optional.of(fee));
            when(gateways.selected()).thenReturn(gateway);
            when(gateway.name()).thenReturn("mock");
            when(gateway.publicKey()).thenReturn("mock_public_key");
            when(payments.saveAndFlush(any(FeePayment.class))).thenAnswer(assignId(PAYMENT_ID));
            when(gateway.createOrder(any(PaymentOrderRequest.class))).thenReturn(ORDER_ID);

            PaymentInitiationResponse response = service.initiate(request(50_000L), KEY);

            // The whole idempotency argument in one assertion. If the order were opened
            // first, two copies of one request would both reach the provider and only then
            // discover the duplicate -- leaving an orphaned order behind every lost race.
            InOrder order = inOrder(payments, gateway);
            order.verify(payments).saveAndFlush(any(FeePayment.class));
            order.verify(gateway).createOrder(any(PaymentOrderRequest.class));
            order.verify(payments).save(any(FeePayment.class));

            ArgumentCaptor<PaymentOrderRequest> opened = ArgumentCaptor.forClass(PaymentOrderRequest.class);
            verify(gateway).createOrder(opened.capture());
            // The receipt is our own row id, which is what makes a provider's settlement
            // report reconcilable against this database without a second lookup table.
            assertThat(opened.getValue().receipt()).isEqualTo(String.valueOf(PAYMENT_ID));
            assertThat(opened.getValue().amountPaise()).isEqualTo(50_000L);
            assertThat(opened.getValue().currency()).isEqualTo("INR");

            assertThat(response.paymentId()).isEqualTo(PAYMENT_ID);
            assertThat(response.feeId()).isEqualTo(FEE_ID);
            assertThat(response.providerOrderId()).isEqualTo(ORDER_ID);
            assertThat(response.provider()).isEqualTo("mock");
            assertThat(response.publicKey()).isEqualTo("mock_public_key");
            assertThat(response.alreadyInitiated()).isFalse();
        }

        @Test
        @DisplayName("a retry with the same key returns the original attempt and opens no second order")
        void aRetryReplaysTheOriginalAttempt() {
            asStudent();
            FeePayment existing = pendingPayment(unpaidFee(50_000L));
            existing.setProviderOrderId(ORDER_ID);
            when(payments.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));
            when(gateways.forProvider(STORED_PROVIDER)).thenReturn(gateway);
            when(gateway.publicKey()).thenReturn("rzp_test_public");

            PaymentInitiationResponse response = service.initiate(request(50_000L), KEY);

            assertThat(response.alreadyInitiated()).isTrue();
            assertThat(response.paymentId()).isEqualTo(PAYMENT_ID);
            assertThat(response.providerOrderId()).isEqualTo(ORDER_ID);
            // The replay is resolved through the provider the row names, so an attempt
            // started before a gateway switch still hands back a usable checkout.
            assertThat(response.provider()).isEqualTo(STORED_PROVIDER);
            verify(payments, never()).saveAndFlush(any());
            verify(payments, never()).save(any());
            verify(gateway, never()).createOrder(any());
            verify(fees, never()).findByIdAndStudentId(anyLong(), anyLong());
        }

        @Test
        @DisplayName("another student's key is reported as taken, and the response says nothing else")
        void anotherStudentsKeyDisclosesNothing() {
            asStudent();
            FeePayment theirs = pendingPayment(unpaidFee(123_456L));
            theirs.getStudent().setId(OTHER_STUDENT_ID);
            theirs.setProviderOrderId("mock_order_theirs");
            when(payments.findByIdempotencyKey(KEY)).thenReturn(Optional.of(theirs));

            assertThatThrownBy(() -> service.initiate(request(50_000L), KEY))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException e = (ApiException) thrown;
                        assertThat(e.getCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                        // Keys are client-chosen and the constraint is global, so this is
                        // reachable by guessing. The message is the same as any other
                        // duplicate's and carries no details, so it cannot be used to read
                        // back somebody else's order reference or amount.
                        assertThat(e.getMessage()).doesNotContain("mock_order_theirs").doesNotContain("123456");
                        assertThat(e.getDetails()).isNull();
                    });
            verify(gateways, never()).forProvider(anyString());
        }

        @Test
        @DisplayName("a settled attempt's key cannot be reused for a fresh checkout")
        void aSettledAttemptsKeyIsRefused() {
            asStudent();
            FeePayment settled = pendingPayment(unpaidFee(50_000L));
            settled.succeed(PROVIDER_PAYMENT_ID, Instant.now());
            when(payments.findByIdempotencyKey(KEY)).thenReturn(Optional.of(settled));

            assertThatThrownBy(() -> service.initiate(request(50_000L), KEY))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException e = (ApiException) thrown;
                        assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_SETTLED);
                        assertThat(e.getMessage()).contains("Idempotency-Key");
                        assertThat(e.getDetails()).containsEntry("status", "SUCCEEDED");
                    });
        }

        @Test
        @DisplayName("another student's invoice is a 404, and the provider is never contacted")
        void anotherStudentsInvoiceIsNotFound() {
            asStudent();
            when(payments.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
            when(fees.findByIdAndStudentId(FEE_ID, STUDENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.initiate(request(50_000L), KEY))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));
            verifyNoInteractions(gateways);
            verify(payments, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("refuses to pay an invoice that is already settled")
        void refusesASettledInvoice() {
            asStudent();
            HostelFee paid = unpaidFee(50_000L);
            paid.applyPayment(50_000L);
            when(payments.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
            when(fees.findByIdAndStudentId(FEE_ID, STUDENT_ID)).thenReturn(Optional.of(paid));

            assertThatThrownBy(() -> service.initiate(request(1_000L), KEY))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException e = (ApiException) thrown;
                        assertThat(e.getCode()).isEqualTo(ErrorCode.FEE_ALREADY_SETTLED);
                        assertThat(e.getDetails()).containsEntry("status", "PAID");
                    });
            verifyNoInteractions(gateways);
            verify(payments, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("refuses more than the invoice has outstanding, before the provider is contacted")
        void refusesAnOverpayment() {
            asStudent();
            HostelFee partly = unpaidFee(50_000L);
            partly.applyPayment(30_000L);
            when(payments.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
            when(fees.findByIdAndStudentId(FEE_ID, STUDENT_ID)).thenReturn(Optional.of(partly));

            // A 400 rather than a refund: naming an amount cannot be used to overpay, and
            // the check happens before any money is asked for.
            assertThatThrownBy(() -> service.initiate(request(20_001L), KEY))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException e = (ApiException) thrown;
                        assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_AMOUNT_INVALID);
                        assertThat(e.getDetails())
                                .containsEntry("outstandingPaise", 20_000L)
                                .containsEntry("amountPaise", 20_001L);
                    });
            verifyNoInteractions(gateways);
            verify(payments, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("allows exactly the outstanding balance on a part-paid invoice")
        void allowsExactlyTheOutstandingBalance() {
            asStudent();
            HostelFee partly = unpaidFee(50_000L);
            partly.applyPayment(30_000L);
            when(payments.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
            when(fees.findByIdAndStudentId(FEE_ID, STUDENT_ID)).thenReturn(Optional.of(partly));
            when(gateways.selected()).thenReturn(gateway);
            when(gateway.name()).thenReturn("mock");
            when(gateway.publicKey()).thenReturn("mock_public_key");
            when(payments.saveAndFlush(any(FeePayment.class))).thenAnswer(assignId(PAYMENT_ID));
            when(gateway.createOrder(any(PaymentOrderRequest.class))).thenReturn(ORDER_ID);

            PaymentInitiationResponse response = service.initiate(request(20_000L), KEY);

            assertThat(response.amountPaise()).isEqualTo(20_000L);
        }

        @Test
        @DisplayName("a gateway outage is a 502 that leaves no half-made attempt behind")
        void aGatewayOutageIsRetryableWithTheSameKey() {
            asStudent();
            when(payments.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
            when(fees.findByIdAndStudentId(FEE_ID, STUDENT_ID)).thenReturn(Optional.of(unpaidFee(50_000L)));
            when(gateways.selected()).thenReturn(gateway);
            when(gateway.name()).thenReturn("mock");
            when(payments.saveAndFlush(any(FeePayment.class))).thenAnswer(assignId(PAYMENT_ID));
            when(gateway.createOrder(any(PaymentOrderRequest.class)))
                    .thenThrow(new PaymentGatewayException("connect timed out"));

            assertThatThrownBy(() -> service.initiate(request(50_000L), KEY))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException e = (ApiException) thrown;
                        // 502, so a client reading the status class alone retries rather
                        // than treating it as its own bad request.
                        assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_ERROR);
                        assertThat(e.getCode().status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(e.getDetails())
                                .containsEntry("provider", "mock")
                                .containsEntry("retryable", true);
                        assertThat(e.getCause()).isInstanceOf(PaymentGatewayException.class);
                    });

            // The row that claimed the key never received an order reference, and the
            // second write never happened. The exception unwinds the transaction, so the
            // key is free again and the retry is a clean first attempt -- which is only
            // true because nothing was charged.
            ArgumentCaptor<FeePayment> claimed = ArgumentCaptor.forClass(FeePayment.class);
            verify(payments).saveAndFlush(claimed.capture());
            assertThat(claimed.getValue().getProviderOrderId()).isNull();
            assertThat(claimed.getValue().getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(payments, never()).save(any());
        }

        @Test
        @DisplayName("a warden cannot start a payment: there is no invoice of theirs to pay")
        void staffCannotPay() {
            when(currentUser.scope())
                    .thenReturn(new AccessScope(ADMIN_USER_ID, Role.ADMIN, null, null));

            assertThatThrownBy(() -> service.initiate(request(50_000L), KEY))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));
            verifyNoInteractions(payments, fees, gateways);
        }
    }

    @Nested
    @DisplayName("settling a callback")
    class Settling {

        @Test
        @DisplayName("credits the invoice under the row lock, using the provider the row names")
        void creditsUnderTheRowLock() {
            HostelFee fee = unpaidFee(50_000L);
            FeePayment payment = pendingPayment(fee);
            when(payments.findByProviderOrderId(ORDER_ID)).thenReturn(List.of(payment));
            when(gateways.forProvider(STORED_PROVIDER)).thenReturn(gateway);
            when(gateway.verify(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE)).thenReturn(true);
            when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(fee));

            FeePaymentResponse response = service.settle(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE);

            // The invoice is read through the locking finder and never through the plain
            // one. This is the assertion that fails if somebody "simplifies" the lock
            // away: the balance would still come out right in a single-threaded test.
            verify(fees).findByIdForUpdate(FEE_ID);
            verify(fees, never()).findById(anyLong());

            // Verification first, then the lock -- so the lock is held for the shortest
            // span, and an unauthentic caller never causes one to be taken at all.
            InOrder order = inOrder(gateway, fees, payments);
            order.verify(gateway).verify(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE);
            order.verify(fees).findByIdForUpdate(FEE_ID);
            order.verify(fees).save(fee);
            order.verify(payments).save(payment);

            // The configured provider is never consulted: a payment started before a
            // gateway switch is still settleable, and a caller cannot choose which secret
            // checks their signature.
            verify(gateways).forProvider(STORED_PROVIDER);
            verify(gateways, never()).selected();

            assertThat(fee.getAmountPaidPaise()).isEqualTo(50_000L);
            assertThat(fee.getStatus()).isEqualTo(FeeStatus.PAID);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payment.getProviderPaymentId()).isEqualTo(PROVIDER_PAYMENT_ID);
            assertThat(payment.getCompletedAt()).isNotNull();

            assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(response.feeId()).isEqualTo(FEE_ID);
            assertThat(response.studentId()).isEqualTo(STUDENT_ID);

            // A provider callback arrives with no session. Settling must not depend on
            // one, or a webhook would be unable to complete a payment the payer started.
            verifyNoInteractions(currentUser);
        }

        @Test
        @DisplayName("a part payment leaves the invoice partially paid rather than paid")
        void aPartPaymentIsPartial() {
            HostelFee fee = unpaidFee(50_000L);
            FeePayment payment = pendingPayment(fee);
            payment.setAmountPaise(20_000L);
            when(payments.findByProviderOrderId(ORDER_ID)).thenReturn(List.of(payment));
            when(gateways.forProvider(STORED_PROVIDER)).thenReturn(gateway);
            when(gateway.verify(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE)).thenReturn(true);
            when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(fee));

            service.settle(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE);

            assertThat(fee.getAmountPaidPaise()).isEqualTo(20_000L);
            assertThat(fee.outstandingPaise()).isEqualTo(30_000L);
            assertThat(fee.getStatus()).isEqualTo(FeeStatus.PARTIALLY_PAID);
        }

        @Test
        @DisplayName("an unknown order reference is a 404")
        void anUnknownOrderReferenceIsNotFound() {
            when(payments.findByProviderOrderId(ORDER_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> service.settle(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));
            verifyNoInteractions(gateways, fees);
        }

        @Test
        @DisplayName("two attempts for one order reference is refused rather than guessed at")
        void twoAttemptsForOneOrderReferenceIsRefused() {
            FeePayment first = pendingPayment(unpaidFee(50_000L));
            FeePayment second = pendingPayment(unpaidFee(50_000L));
            second.setId(PAYMENT_ID + 1);
            when(payments.findByProviderOrderId(ORDER_ID)).thenReturn(List.of(first, second));

            // provider_order_id carries no unique constraint (see the repository), so
            // "exactly one" has to be checked. There is no correct guess: the signature
            // authenticates one pair, and crediting the wrong invoice moves real money to
            // the wrong student. A 500 with both ids logged is the honest outcome.
            assertThatThrownBy(() -> service.settle(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.INTERNAL));
            verifyNoInteractions(gateways, fees);
        }

        @Test
        @DisplayName("a provider with no registered adapter is a gateway error, not a verification failure")
        void aMissingAdapterIsAGatewayError() {
            when(payments.findByProviderOrderId(ORDER_ID))
                    .thenReturn(List.of(pendingPayment(unpaidFee(50_000L))));
            when(gateways.forProvider(STORED_PROVIDER))
                    .thenThrow(new PaymentGatewayException("No adapter is registered for provider 'razorpay'"));

            assertThatThrownBy(() -> service.settle(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException e = (ApiException) thrown;
                        // Not PAYMENT_VERIFICATION_FAILED: the signature may well be
                        // perfectly good, and telling the payer their confirmation is
                        // invalid would send them to retry something that cannot work.
                        assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_ERROR);
                        assertThat(e.getDetails()).containsEntry("provider", STORED_PROVIDER);
                    });
            verify(fees, never()).findByIdForUpdate(anyLong());
        }

        @Test
        @DisplayName("a bad signature is rejected before the payment's status is consulted")
        void aBadSignatureIsRejectedBeforeTheStatus() {
            FeePayment alreadySettled = pendingPayment(unpaidFee(50_000L));
            alreadySettled.succeed(PROVIDER_PAYMENT_ID, Instant.now());
            when(payments.findByProviderOrderId(ORDER_ID)).thenReturn(List.of(alreadySettled));
            when(gateways.forProvider(STORED_PROVIDER)).thenReturn(gateway);
            when(gateway.verify(ORDER_ID, PROVIDER_PAYMENT_ID, "forged")).thenReturn(false);

            // The row is settled *and* the signature is bad. The status check happening
            // second is what makes the two indistinguishable from outside: an unauthentic
            // caller gets PAYMENT_VERIFICATION_FAILED either way and cannot probe which
            // order references have been paid.
            assertThatThrownBy(() -> service.settle(ORDER_ID, PROVIDER_PAYMENT_ID, "forged"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException e = (ApiException) thrown;
                        assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_VERIFICATION_FAILED);
                        assertThat(e.getDetails()).containsEntry("providerOrderId", ORDER_ID);
                    });
            verify(fees, never()).findByIdForUpdate(anyLong());
            verify(payments, never()).save(any());
        }

        @Test
        @DisplayName("a redelivered callback is a 409 and credits nothing a second time")
        void aRedeliveredCallbackCreditsNothingTwice() {
            FeePayment settled = pendingPayment(unpaidFee(50_000L));
            settled.succeed(PROVIDER_PAYMENT_ID, Instant.now());
            when(payments.findByProviderOrderId(ORDER_ID)).thenReturn(List.of(settled));
            when(gateways.forProvider(STORED_PROVIDER)).thenReturn(gateway);
            when(gateway.verify(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE)).thenReturn(true);

            // Every gateway redelivers webhooks. This is the check that stops the second
            // delivery crediting the invoice again.
            assertThatThrownBy(() -> service.settle(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException e = (ApiException) thrown;
                        assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_SETTLED);
                        assertThat(e.getDetails())
                                .containsEntry("paymentId", PAYMENT_ID)
                                .containsEntry("status", "SUCCEEDED");
                    });
            verify(fees, never()).findByIdForUpdate(anyLong());
            verify(fees, never()).save(any());
        }

        @Test
        @DisplayName("an invoice settled mid-checkout leaves the payment PENDING for reconciliation")
        void anInvoiceSettledMidCheckoutLeavesThePaymentPending() {
            HostelFee fee = unpaidFee(50_000L);
            FeePayment payment = pendingPayment(fee);
            // Another attempt settled the invoice while this checkout was open.
            fee.applyPayment(50_000L);
            when(payments.findByProviderOrderId(ORDER_ID)).thenReturn(List.of(payment));
            when(gateways.forProvider(STORED_PROVIDER)).thenReturn(gateway);
            when(gateway.verify(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE)).thenReturn(true);
            when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(fee));

            assertThatThrownBy(() -> service.settle(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException e = (ApiException) thrown;
                        assertThat(e.getCode()).isEqualTo(ErrorCode.FEE_ALREADY_SETTLED);
                        assertThat(e.getMessage()).contains("reconcile");
                        assertThat(e.getDetails())
                                .containsEntry("feeId", FEE_ID)
                                .containsEntry("paymentId", PAYMENT_ID);
                    });

            // The money is captured at the provider, so the row is left PENDING. Marking
            // it FAILED would assert the money is not there, and a reconciliation that
            // starts from a FAILED row starts from a lie. Nothing is written at all.
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getCompletedAt()).isNull();
            assertThat(payment.getProviderPaymentId()).isNull();
            verify(payments, never()).save(any());
            verify(fees, never()).save(any());
        }

        @Test
        @DisplayName("a cancelled invoice is the same refusal, not an exception escaping the service")
        void aCancelledInvoiceIsRefusedTheSameWay() {
            HostelFee fee = unpaidFee(50_000L);
            FeePayment payment = pendingPayment(fee);
            fee.cancel();
            when(payments.findByProviderOrderId(ORDER_ID)).thenReturn(List.of(payment));
            when(gateways.forProvider(STORED_PROVIDER)).thenReturn(gateway);
            when(gateway.verify(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE)).thenReturn(true);
            when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(fee));

            // HostelFee.applyPayment throws IllegalStateException here and
            // IllegalArgumentException for an overpayment. Both are caught, because both
            // mean the same thing to the payer and neither should reach the client as a 500.
            assertThatThrownBy(() -> service.settle(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.FEE_ALREADY_SETTLED));
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(payments, never()).save(any());
        }

        @Test
        @DisplayName("an invoice that has vanished under the payment is a 500, not a 404")
        void aVanishedInvoiceIsInternal() {
            FeePayment payment = pendingPayment(unpaidFee(50_000L));
            when(payments.findByProviderOrderId(ORDER_ID)).thenReturn(List.of(payment));
            when(gateways.forProvider(STORED_PROVIDER)).thenReturn(gateway);
            when(gateway.verify(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE)).thenReturn(true);
            when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.empty());

            // The payment row has a NOT NULL foreign key to the invoice, so this is
            // impossible rather than merely unusual. A 404 would tell the payer they got
            // the reference wrong; they did not, and somebody needs to look at the database.
            assertThatThrownBy(() -> service.settle(ORDER_ID, PROVIDER_PAYMENT_ID, SIGNATURE))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.INTERNAL));
            verify(payments, never()).save(any());
        }
    }

    @Nested
    @DisplayName("reading the ledger")
    class Reading {

        @Test
        @DisplayName("one invoice's history is resolved through the scoped finder before any payment is read")
        void oneInvoicesHistoryIsScopedFirst() {
            asAdmin();
            when(fees.findByIdAndStudentGenderIn(eq(FEE_ID), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.forFee(FEE_ID))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));

            // The ordering is the authorization. Reading the payments first and filtering
            // afterwards would disclose that a fee id exists in another hostel, and how
            // many times it has been paid against.
            verify(payments, never()).findByFeeIdOrderByCreatedAtDesc(anyLong());
        }
    }

    // ---------------------------------------------------------------------------------
    // Fixtures. Built through the domain's own methods wherever a state is reachable
    // that way -- a fixture that sets FeeStatus.PAID with a setter can describe an
    // invoice HostelFee would never produce, and then the test proves nothing.
    // ---------------------------------------------------------------------------------

    private void asStudent() {
        lenient().when(currentUser.scope())
                .thenReturn(new AccessScope(STUDENT_USER_ID, Role.STUDENT, null, STUDENT_ID));
    }

    private void asAdmin() {
        lenient().when(currentUser.scope())
                .thenReturn(new AccessScope(ADMIN_USER_ID, Role.ADMIN, null, null));
    }

    private static PaymentInitiationRequest request(long amountPaise) {
        return new PaymentInitiationRequest(FEE_ID, amountPaise);
    }

    private static HostelFee unpaidFee(long amountPaise) {
        HostelFee fee = new HostelFee();
        fee.setId(FEE_ID);
        fee.setStudent(student());
        fee.setTitle("Hostel fee");
        fee.setAcademicYear("2026-27");
        fee.setSemester("ODD");
        fee.setAmountPaise(amountPaise);
        fee.setDueDate(LocalDate.of(2026, 9, 30));
        return fee;
    }

    private static FeePayment pendingPayment(HostelFee fee) {
        FeePayment payment = new FeePayment();
        payment.setId(PAYMENT_ID);
        payment.setFee(fee);
        payment.setStudent(fee.getStudent());
        payment.setAmountPaise(fee.getAmountPaise());
        payment.setProvider(STORED_PROVIDER);
        payment.setIdempotencyKey(KEY);
        payment.setCreatedAt(Instant.parse("2026-08-01T10:00:00Z"));
        return payment;
    }

    private static Student student() {
        Student student = new Student();
        student.setId(STUDENT_ID);
        student.setRollNumber("22CS001");
        student.setGender(Gender.M);
        student.setYearOfStudy(2);
        return student;
    }

    /** Stands in for the identity column, so the receipt sent to the provider is assertable. */
    private static Answer<FeePayment> assignId(long id) {
        return invocation -> {
            FeePayment payment = invocation.getArgument(0);
            payment.setId(id);
            return payment;
        };
    }
}
