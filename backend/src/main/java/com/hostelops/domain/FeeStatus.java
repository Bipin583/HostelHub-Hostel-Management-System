package com.hostelops.domain;

/**
 * How much of an invoice has been settled.
 *
 * <p>Derived from the amounts rather than set by hand -- see
 * {@code HostelFee.recalculateStatus()}. Keeping it as a stored column as well is
 * a deliberate denormalisation: the outstanding-fees query filters on it, and a
 * partial index on a computed comparison would have to be an expression index
 * that every writer has to keep in mind. One derived column, one place that
 * derives it.
 */
public enum FeeStatus {
    UNPAID,
    PARTIALLY_PAID,
    PAID,
    /** Written off. Excluded from collection-rate arithmetic rather than counted as unpaid. */
    CANCELLED
}
