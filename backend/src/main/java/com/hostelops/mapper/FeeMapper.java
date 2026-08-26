package com.hostelops.mapper;

import com.hostelops.domain.HostelFee;
import com.hostelops.dto.fee.FeeCollectionResponse;
import com.hostelops.dto.fee.FeeResponse;
import com.hostelops.repository.FeeCollectionTotals;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FeeMapper {

    private final StudentMapper studentMapper;

    public FeeMapper(StudentMapper studentMapper) {
        this.studentMapper = studentMapper;
    }

    /**
     * @param today the date overdueness is judged against. Passed in for the same
     *              reason as everywhere else -- one date for every row in a response,
     *              and a test that does not have to wait until tomorrow.
     */
    public FeeResponse toResponse(HostelFee fee, LocalDate today) {
        return new FeeResponse(
                fee.getId(),
                studentMapper.toSummary(fee.getStudent()),
                fee.getTitle(),
                fee.getAcademicYear(),
                fee.getSemester(),
                fee.getAmountPaise(),
                fee.getAmountPaidPaise(),
                // Read from the entity rather than subtracted here. HostelFee already owns
                // the rule that a cancelled invoice owes nothing and that the figure never
                // goes negative; recomputing it would be a second copy of that rule, free
                // to drift.
                fee.outstandingPaise(),
                fee.getDueDate(),
                fee.getDescription(),
                fee.getStatus(),
                fee.isOverdue(today));
    }

    /**
     * Assembles the collections dashboard from the per-term totals.
     *
     * <p>The grand total is summed here rather than by a second aggregate query. One
     * query for the rows and arithmetic over the same rows guarantees the headline
     * figure and the table agree; two queries against a moving table can disagree by a
     * payment that landed between them, and the mismatch would show up on screen.
     *
     * <p>The overall rate divides summed collected by summed billed. Averaging the
     * per-term rates would weight a term with one invoice equally against a term with
     * four hundred -- Simpson's paradox on a dashboard, and the direction of the error
     * depends on which terms happen to be sparse.
     */
    public FeeCollectionResponse toCollectionResponse(List<FeeCollectionTotals> totals) {
        long invoices = 0;
        long paid = 0;
        long overdue = 0;
        long billed = 0;
        long collected = 0;
        List<FeeCollectionResponse.Term> terms = totals.stream().map(FeeMapper::toTerm).toList();
        for (FeeCollectionTotals t : totals) {
            invoices += t.invoiceCount();
            paid += t.paidInvoiceCount();
            overdue += t.overdueCount();
            billed += t.billedPaise();
            collected += t.collectedPaise();
        }
        FeeCollectionResponse.Totals grand = new FeeCollectionResponse.Totals(
                invoices,
                paid,
                overdue,
                billed,
                collected,
                Math.max(0L, billed - collected),
                billed == 0 ? 0d : (double) collected / (double) billed);
        return new FeeCollectionResponse(grand, terms);
    }

    private static FeeCollectionResponse.Term toTerm(FeeCollectionTotals t) {
        return new FeeCollectionResponse.Term(
                t.academicYear(),
                t.semester(),
                t.invoiceCount(),
                t.paidInvoiceCount(),
                t.overdueCount(),
                t.billedPaise(),
                t.collectedPaise(),
                t.outstandingPaise(),
                t.collectionRate());
    }
}
