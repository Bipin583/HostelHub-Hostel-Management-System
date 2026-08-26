package com.hostelops.repository;

import com.hostelops.domain.FeePayment;
import com.hostelops.domain.Gender;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface FeePaymentRepository extends Repository<FeePayment, Long> {

    FeePayment save(FeePayment payment);

    /**
     * Writes the row and flushes immediately.
     *
     * <p>Used when starting a payment so {@code uq_fee_payments_idempotency} fires inside
     * the service call, where a duplicate key can be translated into "here is the
     * original attempt", rather than at commit time after the method has returned a
     * second payment to the caller.
     */
    FeePayment saveAndFlush(FeePayment payment);

    Optional<FeePayment> findById(Long id);

    /**
     * The attempt a given client request already created, if any.
     *
     * <p>The read half of the idempotency guarantee; {@code uq_fee_payments_idempotency}
     * is the half that holds when two copies of the same request arrive together. The
     * lookup keeps the ordinary retry cheap, the constraint keeps the race correct.
     */
    Optional<FeePayment> findByIdempotencyKey(String idempotencyKey);

    /**
     * The attempt a provider callback refers to.
     *
     * <p>Backed by the partial unique index {@code uq_fee_payments_provider_ref}, which
     * only covers rows that have a provider reference -- so the many {@code PENDING}
     * rows with a NULL reference do not all collide with each other.
     */
    Optional<FeePayment> findByProviderAndProviderPaymentId(String provider, String providerPaymentId);

    Optional<FeePayment> findByProviderAndProviderOrderId(String provider, String providerOrderId);

    /**
     * Every attempt carrying a given provider order reference.
     *
     * <p>This is how a callback finds its row, and it deliberately does not take a
     * provider: the provider is what the stored row *tells* us, and a caller who could
     * name it would be choosing which secret verifies their signature. See
     * {@code PaymentService.settle}.
     *
     * <p>A list rather than an {@code Optional}, because the schema does not promise
     * uniqueness here. {@code uq_fee_payments_provider_ref} covers
     * {@code (provider, provider_payment_id)} -- the reference a settled row carries --
     * and nothing constrains {@code provider_order_id} at all. Within one provider an
     * order id is unique because the provider issues it, but that is the provider's
     * promise and not this database's, and a finder that returned
     * {@code Optional} would turn a broken promise into an
     * {@code IncorrectResultSizeDataAccessException} from inside the payment path. The
     * service requires exactly one row and says so out loud otherwise.
     *
     * <p>Unindexed, and knowingly: there is no index on {@code provider_order_id} in V1.
     * At this table's size a scan is cheaper than the argument for changing a frozen
     * migration, and the fix -- a partial index mirroring
     * {@code uq_fee_payments_provider_ref} -- is a one-line V2 whenever the volume
     * justifies it.
     */
    List<FeePayment> findByProviderOrderId(String providerOrderId);

    /** Attempts against one invoice, newest first. Uses {@code idx_fee_payments_fee}. */
    List<FeePayment> findByFeeIdOrderByCreatedAtDesc(Long feeId);

    Optional<FeePayment> findByIdAndStudentId(Long id, Long studentId);

    /** A student's payment history. Uses {@code idx_fee_payments_student}. */
    @Query(value = """
            select p from FeePayment p
            join fetch p.fee f
            where p.student.id = :studentId
            order by p.createdAt desc
            """,
            countQuery = "select count(p) from FeePayment p where p.student.id = :studentId")
    Page<FeePayment> findForStudent(@Param("studentId") Long studentId, Pageable pageable);

    /** The warden's or admin's ledger view, scoped by the students they may see. */
    @Query(value = """
            select p from FeePayment p
            join fetch p.fee f
            join fetch p.student s
            join fetch s.user u
            where s.gender in :genders
            order by p.createdAt desc
            """,
            countQuery = """
                    select count(p) from FeePayment p where p.student.gender in :genders
                    """)
    Page<FeePayment> findInScope(@Param("genders") Collection<Gender> genders, Pageable pageable);
}
