package com.hostelops.repository;

/**
 * Enough of a {@code fee_payments} row to verify a callback against it, without loading the
 * entity.
 *
 * <p>Exists so {@code PaymentService.settle} can pick the adapter and check the signature
 * before the row is ever managed, and then load it exactly once through
 * {@link FeePaymentRepository#findByIdForUpdate}. Loading the entity first and locking it
 * afterwards would not work: Hibernate returns the instance already in the session and
 * discards the state the {@code FOR UPDATE} just read, so the status check would run against
 * a copy taken before the competing transaction committed. The lock would be held and the
 * race would survive it. Reading the two fields as scalars keeps the session empty until the
 * locking read, which is the only read there is.
 *
 * <p>A record rather than a Spring Data interface projection because this is a JPQL
 * constructor expression, which needs a real constructor to call.
 *
 * @param id       the attempt's own id, the key the locking finder takes
 * @param provider which adapter opened the order, and therefore whose secret verifies the
 *                 signature -- taken from the row rather than from the callback, so the
 *                 caller cannot choose
 */
public record FeePaymentAttemptRef(Long id, String provider) {
}
