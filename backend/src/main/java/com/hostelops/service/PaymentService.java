package com.hostelops.service;

import com.hostelops.audit.AuditEntity;
import com.hostelops.audit.Audited;
import com.hostelops.domain.AuditAction;
import com.hostelops.domain.FeePayment;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelFee;
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
import com.hostelops.security.CurrentUserProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taking payments, and the only code that credits an invoice.
 *
 * <h2>Two guarantees, two different mechanisms</h2>
 *
 * <p>This class is where the project's two hardest concurrency claims live, and they are
 * defended differently because they are different problems.
 *
 * <p><b>A request must not charge twice.</b> That is {@link #initiate}, and the mechanism is
 * {@code uq_fee_payments_idempotency} over the client's {@code Idempotency-Key}. A lookup
 * handles the ordinary retry cheaply; the constraint handles two copies of the same request
 * arriving together, which no lookup can. The window between "check for a duplicate" and
 * "insert one" is exactly the bug being avoided, so the check is not what the guarantee
 * rests on.
 *
 * <p><b>Two payments must not erase each other.</b> That is {@link #settle}, and the
 * mechanism is the row lock from {@code HostelFeeRepository.findByIdForUpdate}. Crediting is
 * read-modify-write on {@code amount_paid_paise}: under READ COMMITTED two callbacks landing
 * together both read the old balance and the second write loses the first, so a student who
 * pays twice has one payment vanish from the invoice while its {@code fee_payments} row
 * still says {@code SUCCEEDED}. It is the double-booking race from the allocation code in
 * a different table, and it takes the same fix.
 *
 * <h2>Why a gateway failure rolls the attempt back</h2>
 *
 * <p>{@link #initiate} claims the idempotency key by flushing the row, and only then opens
 * the order at the provider. If the provider is unreachable, the {@link ApiException} unwinds
 * the transaction and the claimed key goes with it -- so retrying with the same key is a
 * clean first attempt rather than a replay of a broken one. That ordering matters: opening
 * the order first would leave orphaned orders at the provider every time the insert lost a
 * race, and inserting without rolling back would strand the key against a row that has no
 * order to pay.
 *
 * <p>It follows that a committed {@code PENDING} row always carries a
 * {@code provider_order_id}, which is what lets {@link #replay} hand one straight back to a
 * retrying client.
 *
 * <h2>Nothing here believes the caller</h2>
 *
 * <p>{@link #settle} takes an order reference, a payment reference and a signature. It does
 * not take an amount -- that comes off the {@code fee_payments} row -- and it does not take
 * an outcome. The signature is the entire authorization for moving money, verified by the
 * adapter named on the stored row rather than by the currently-configured provider, so a
 * payment started before a gateway switch is still settleable afterwards.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Matches {@code fee_payments.idempotency_key}. Over this is a 400, not a truncation. */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 80;

    private final FeePaymentRepository payments;
    private final HostelFeeRepository fees;
    private final PaymentGatewayRegistry gateways;
    private final PaymentMapper paymentMapper;
    private final CurrentUserProvider currentUser;

    public PaymentService(
            FeePaymentRepository payments,
            HostelFeeRepository fees,
            PaymentGatewayRegistry gateways,
            PaymentMapper paymentMapper,
            CurrentUserProvider currentUser) {
        this.payments = payments;
        this.fees = fees;
        this.gateways = gateways;
        this.paymentMapper = paymentMapper;
        this.currentUser = currentUser;
    }

    /**
     * Starts a payment against one of the caller's own invoices.
     *
     * <p>The invoice is loaded by {@code (id, studentId)}, so a student cannot open a
     * checkout against somebody else's fee -- and gets the same 404 either way.
     *
     * <p>The amount is taken from the request rather than assumed to be the full balance,
     * because part payments are a requirement, and is then checked against what is actually
     * outstanding. Naming an amount therefore cannot be used to overpay.
     *
     * @param idempotencyKey the {@code Idempotency-Key} header, required. Two requests
     *                       carrying the same key produce one payment; see the class Javadoc
     *                       for which half of that is the lookup and which is the constraint.
     */
    @Audited(entity = AuditEntity.PAYMENT, action = AuditAction.CREATE)
    @Transactional
    public PaymentInitiationResponse initiate(PaymentInitiationRequest request, String idempotencyKey) {
        Long studentId = currentUser.scope().requireStudentId();
        String key = requireKey(idempotencyKey);

        // The cheap half of the guarantee: an ordinary retry finds its own earlier attempt.
        Optional<FeePayment> replayed = payments.findByIdempotencyKey(key);
        if (replayed.isPresent()) {
            return replay(replayed.get(), studentId);
        }

        HostelFee fee = fees.findByIdAndStudentId(request.feeId(), studentId)
                .orElseThrow(() -> ApiException.notFound("fee", request.feeId()));
        requirePayable(fee, request.amountPaise());

        PaymentGateway gateway = gateways.selected();
        FeePayment payment = new FeePayment();
        payment.setFee(fee);
        payment.setStudent(fee.getStudent());
        payment.setAmountPaise(request.amountPaise());
        payment.setProvider(gateway.name());
        payment.setIdempotencyKey(key);

        // Claims the key before the provider is contacted. A concurrent duplicate fails
        // here on uq_fee_payments_idempotency, which GlobalExceptionHandler already reports
        // as DUPLICATE_RESOURCE -- the loser's transaction unwinds, and its retry finds the
        // winner's row through the lookup above.
        payments.saveAndFlush(payment);

        String providerOrderId;
        try {
            providerOrderId = gateway.createOrder(new PaymentOrderRequest(
                    payment.getAmountPaise(), payment.getCurrency(), String.valueOf(payment.getId())));
        } catch (PaymentGatewayException e) {
            // Unwinds the insert above, deliberately. Nothing was charged, so the honest
            // state is "this attempt never happened" and the same key may be used again.
            log.warn("Could not open a {} order for fee {}", gateway.name(), fee.getId(), e);
            throw new ApiException(ErrorCode.PAYMENT_GATEWAY_ERROR,
                    "The payment provider could not be reached. Please try again.",
                    Map.of("provider", gateway.name(), "retryable", true), e);
        }

        payment.setProviderOrderId(providerOrderId);
        payments.save(payment);

        return new PaymentInitiationResponse(
                payment.getId(),
                fee.getId(),
                payment.getAmountPaise(),
                payment.getCurrency(),
                gateway.name(),
                providerOrderId,
                gateway.publicKey(),
                false);
    }

    /**
     * Settles a payment from a verified provider callback.
     *
     * <p>The order of the four steps is the security argument, and each one is load-bearing.
     *
     * <p>First the row is found, which is what says who the provider is. Verification uses
     * the adapter named on that row rather than the configured one, so the caller cannot
     * choose which secret their signature is checked against -- a callback that named its
     * own provider would let anyone pick the gateway whose secret they happen to know.
     *
     * <p>Second the signature is checked, before anything about the payment's state is
     * revealed or acted on. An unauthentic caller therefore cannot tell a settled payment
     * from an unsettled one.
     *
     * <p>Third the {@code PENDING} check. Gateways redeliver callbacks -- all of them do --
     * and re-settling would credit the invoice a second time for one payment. A 409 rather
     * than a silent success because the duplicate is worth surfacing, and
     * {@code PAYMENT_ALREADY_SETTLED} says exactly which case it is.
     *
     * <p>Fourth the invoice is locked and credited. The lock is taken here and not earlier so
     * it is held for the shortest possible span, and it covers the only read-modify-write in
     * the method.
     *
     * <p>Deliberately three parameters rather than the bound request record: the aspect that
     * writes the audit trail redacts by parameter name, and a signature reaching
     * {@code payload_diff} inside an innocuously-named record is precisely the gap
     * {@code AuditAspect} documents. Naming the parameter {@code signature} closes it. A
     * transposed order id and payment id is not a silent failure either -- the signature is
     * computed over the two in a fixed order, so verification fails immediately in every
     * environment, the mock included.
     */
    @Audited(entity = AuditEntity.PAYMENT, action = AuditAction.UPDATE)
    @Transactional
    public FeePaymentResponse settle(String providerOrderId, String providerPaymentId, String signature) {
        FeePayment payment = requireOneAttemptFor(providerOrderId);

        PaymentGateway gateway;
        try {
            gateway = gateways.forProvider(payment.getProvider());
        } catch (PaymentGatewayException e) {
            // The adapter that opened this order is no longer deployed. Not the caller's
            // fault and not retryable by them, so it is reported as a gateway problem.
            log.error("Payment {} was taken through provider '{}', for which no adapter is registered",
                    payment.getId(), payment.getProvider(), e);
            throw new ApiException(ErrorCode.PAYMENT_GATEWAY_ERROR,
                    "This payment cannot be verified at the moment.",
                    Map.of("provider", payment.getProvider()), e);
        }

        if (!gateway.verify(providerOrderId, providerPaymentId, signature)) {
            log.warn("Rejected a {} callback for payment {}: signature did not verify",
                    payment.getProvider(), payment.getId());
            throw new ApiException(ErrorCode.PAYMENT_VERIFICATION_FAILED,
                    "This payment confirmation could not be verified",
                    Map.of("providerOrderId", providerOrderId));
        }

        if (payment.getStatus().isSettled()) {
            throw new ApiException(ErrorCode.PAYMENT_ALREADY_SETTLED,
                    "This payment attempt is already " + payment.getStatus(),
                    Map.of("paymentId", payment.getId(), "status", payment.getStatus().name()));
        }

        HostelFee fee = fees.findByIdForUpdate(payment.getFee().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL,
                        "The invoice behind payment " + payment.getId() + " has gone"));

        try {
            fee.applyPayment(payment.getAmountPaise());
        } catch (IllegalArgumentException | IllegalStateException e) {
            // The invoice cannot absorb this payment: another one settled, or it was
            // cancelled, while this checkout was open. Money has been captured at the
            // provider by now, so the row is deliberately left PENDING rather than marked
            // FAILED -- FAILED would assert the money is not there, and it is. A PENDING
            // row with a provider payment reference against a settled invoice is the state
            // that needs a human and a refund, and it is loud in the log.
            log.error("Payment {} of {} paise cannot be credited to fee {} (status {}, {} paise "
                            + "outstanding). It is captured at {} and needs reconciliation.",
                    payment.getId(), payment.getAmountPaise(), fee.getId(), fee.getStatus(),
                    fee.outstandingPaise(), payment.getProvider(), e);
            throw new ApiException(ErrorCode.FEE_ALREADY_SETTLED,
                    "This invoice was settled while the payment was in progress. "
                            + "The hostel office has been alerted to reconcile it.",
                    Map.of("feeId", fee.getId(), "paymentId", payment.getId()), e);
        }

        fees.save(fee);
        payment.succeed(providerPaymentId, Instant.now());
        payments.save(payment);

        log.info("Payment {} of {} paise credited to fee {} via {}",
                payment.getId(), payment.getAmountPaise(), fee.getId(), payment.getProvider());
        return paymentMapper.toResponse(payment);
    }

    /** The caller's own payment history, newest first. */
    @Transactional(readOnly = true)
    public Page<FeePaymentResponse> mine(Pageable pageable) {
        Long studentId = currentUser.scope().requireStudentId();
        return payments.findForStudent(studentId, pageable).map(paymentMapper::toResponse);
    }

    /** The staff ledger: every payment by a student the caller may see, newest first. */
    @Transactional(readOnly = true)
    public Page<FeePaymentResponse> ledger(Pageable pageable) {
        Set<Gender> genders = currentUser.scope().visibleGenders();
        return payments.findInScope(genders, pageable).map(paymentMapper::toResponse);
    }

    /**
     * Every attempt against one invoice, newest first.
     *
     * <p>The invoice is resolved through the scoped finder first, so this cannot be used to
     * read the payment history of a student in another hostel by naming their fee id.
     */
    @Transactional(readOnly = true)
    public List<FeePaymentResponse> forFee(Long feeId) {
        HostelFee fee = fees.findByIdAndStudentGenderIn(feeId, currentUser.scope().visibleGenders())
                .orElseThrow(() -> ApiException.notFound("fee", feeId));
        return payments.findByFeeIdOrderByCreatedAtDesc(fee.getId()).stream()
                .map(paymentMapper::toResponse)
                .toList();
    }

    /**
     * The single attempt a callback refers to.
     *
     * <p>The lookup is by order reference alone, on purpose -- see {@link #settle}. The
     * repository returns a list because {@code provider_order_id} carries no unique
     * constraint, so the "exactly one" this method needs has to be checked rather than
     * assumed.
     *
     * <p>Two rows is not a caller error and there is no correct guess available: the
     * signature authenticates one {@code (order, payment)} pair, and crediting the wrong
     * invoice with it would move real money to the wrong student. So it is a 500 with both
     * ids in the log, which is what an operator needs to unpick it.
     */
    private FeePayment requireOneAttemptFor(String providerOrderId) {
        List<FeePayment> candidates = payments.findByProviderOrderId(providerOrderId);
        if (candidates.isEmpty()) {
            throw ApiException.notFound("payment order", providerOrderId);
        }
        if (candidates.size() > 1) {
            log.error("Order reference '{}' is recorded against {} payments ({}). A callback for it "
                            + "cannot be attributed and has been refused.",
                    providerOrderId, candidates.size(),
                    candidates.stream().map(FeePayment::getId).toList());
            throw new ApiException(ErrorCode.INTERNAL,
                    "This payment confirmation could not be matched to a single payment");
        }
        return candidates.get(0);
    }

    /**
     * Hands back an attempt the caller has already created.
     *
     * <p>The ownership check is not optional. Idempotency keys are chosen by the client and
     * {@code uq_fee_payments_idempotency} is global rather than per student, so without this
     * a student who guessed another's key would be handed their order reference and amount.
     * A key that belongs to somebody else is reported as taken, in the same words as any
     * other duplicate, so the response does not confirm whose it is.
     *
     * <p>A settled attempt is refused rather than replayed. Returning a paid order to a
     * client that is about to open a checkout with it invites a second payment for the same
     * fee; a fresh attempt needs a fresh key, and the status says why.
     */
    private PaymentInitiationResponse replay(FeePayment existing, Long studentId) {
        if (!Objects.equals(existing.getStudent().getId(), studentId)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "This payment request was already submitted");
        }
        if (existing.getStatus().isSettled()) {
            throw new ApiException(ErrorCode.PAYMENT_ALREADY_SETTLED,
                    "The payment for this request is already " + existing.getStatus()
                            + ". Start a new payment with a new Idempotency-Key.",
                    Map.of("paymentId", existing.getId(), "status", existing.getStatus().name()));
        }
        PaymentGateway gateway = gateways.forProvider(existing.getProvider());
        return new PaymentInitiationResponse(
                existing.getId(),
                existing.getFee().getId(),
                existing.getAmountPaise(),
                existing.getCurrency(),
                existing.getProvider(),
                existing.getProviderOrderId(),
                gateway.publicKey(),
                true);
    }

    /**
     * Refuses a payment the invoice cannot take.
     *
     * <p>Checked before the provider is contacted, so an overpayment is a 400 rather than a
     * refund. {@link HostelFee#applyPayment} enforces the same rule again at credit time and
     * {@code ck_hostel_fees_paid} enforces it a third time in the database -- three layers,
     * because the balance can move between this check and the callback that acts on it.
     */
    private static void requirePayable(HostelFee fee, long amountPaise) {
        if (fee.isSettled()) {
            throw new ApiException(ErrorCode.FEE_ALREADY_SETTLED,
                    "This invoice is already " + fee.getStatus(),
                    Map.of("feeId", fee.getId(), "status", fee.getStatus().name()));
        }
        long outstanding = fee.outstandingPaise();
        if (amountPaise > outstanding) {
            throw new ApiException(ErrorCode.PAYMENT_AMOUNT_INVALID,
                    "This invoice has %d paise outstanding; %d paise cannot be paid against it"
                            .formatted(outstanding, amountPaise),
                    Map.of("feeId", fee.getId(), "outstandingPaise", outstanding,
                            "amountPaise", amountPaise));
        }
    }

    /**
     * Validates the {@code Idempotency-Key} header.
     *
     * <p>Required, not defaulted. Generating a key server-side when one is missing would
     * make every request unique and quietly turn the guarantee off for the clients most
     * likely to need it. Length is checked here so an over-long key is a 400 naming the
     * header rather than a database error naming a column.
     */
    private static String requireKey(String idempotencyKey) {
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "An Idempotency-Key header is required to start a payment",
                    Map.of("header", "Idempotency-Key"));
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters",
                    Map.of("header", "Idempotency-Key", "length", key.length()));
        }
        return key;
    }
}
