package com.hostelops.repository;

/**
 * Billed-versus-collected totals for one term.
 *
 * <p>All money in paise, as everywhere else. {@code overdueCount} is computed against
 * the caller's idea of today rather than {@code now()} inside the query, so a test can
 * ask what the dashboard looked like on a given date.
 */
public record FeeCollectionTotals(
        String academicYear,
        String semester,
        long invoiceCount,
        long billedPaise,
        long collectedPaise,
        long paidInvoiceCount,
        long overdueCount) {

    public long outstandingPaise() {
        return Math.max(0L, billedPaise - collectedPaise);
    }

    /**
     * Collected as a fraction of billed, 0..1.
     *
     * <p>Zero rather than a division by zero when nothing was billed. A term with no
     * invoices has no collection rate, and reporting it as 100% would put a reassuring
     * number on a term nobody has billed yet.
     */
    public double collectionRate() {
        return billedPaise == 0 ? 0d : (double) collectedPaise / (double) billedPaise;
    }
}
