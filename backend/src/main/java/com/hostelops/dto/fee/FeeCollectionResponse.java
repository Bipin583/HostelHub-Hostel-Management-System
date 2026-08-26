package com.hostelops.dto.fee;

import java.util.List;

/**
 * The collections dashboard: billed against collected, one row per term.
 *
 * <p>Per term rather than one grand total, for the reason
 * {@code HostelFeeRepository.findCollectionTotals} gives: a good rate on this
 * semester and a bad one on last year average out to a number that describes neither.
 *
 * <p>{@code collectionRate} is a fraction in 0..1, not a percentage. One convention,
 * fixed at the boundary -- a field that is sometimes 0.87 and sometimes 87 is a
 * formatting bug waiting for whichever client guesses wrong.
 *
 * <p>{@code totals} is the sum across the terms present, computed server-side so the
 * dashboard's headline figure and its table cannot disagree. Its own rate is the ratio
 * of the summed halves, not the mean of the per-term rates: averaging rates would give
 * a term with one invoice the same weight as a term with four hundred.
 */
public record FeeCollectionResponse(Totals totals, List<Term> terms) {

    public record Term(
            String academicYear,
            String semester,
            long invoiceCount,
            long paidInvoiceCount,
            long overdueCount,
            long billedPaise,
            long collectedPaise,
            long outstandingPaise,
            double collectionRate) {
    }

    public record Totals(
            long invoiceCount,
            long paidInvoiceCount,
            long overdueCount,
            long billedPaise,
            long collectedPaise,
            long outstandingPaise,
            double collectionRate) {
    }
}
