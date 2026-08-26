package com.hostelops.repository;

import com.hostelops.domain.FeeStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelFee;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface HostelFeeRepository extends Repository<HostelFee, Long> {

    HostelFee save(HostelFee fee);

    HostelFee saveAndFlush(HostelFee fee);

    Optional<HostelFee> findById(Long id);

    Optional<HostelFee> findByIdAndStudentGenderIn(Long id, Collection<Gender> genders);

    Optional<HostelFee> findByIdAndStudentId(Long id, Long studentId);

    /**
     * Takes a row-level write lock on the invoice, then returns it.
     *
     * <p>The same pattern as {@code RoomRepository.findByIdForUpdate}, for the same
     * reason. Crediting a payment is read-modify-write on {@code amount_paid_paise}:
     * under READ COMMITTED two concurrent payments both read the old balance and the
     * second write erases the first, so a student who pays twice in the same second has
     * one of the payments vanish from the invoice while the {@code fee_payments} row
     * says it succeeded. Locking the invoice serialises the credit.
     *
     * <p>{@code ck_hostel_fees_paid} is the backstop underneath: an overpayment that
     * slipped past the application check cannot be committed at all.
     *
     * <p>Deliberately unscoped, like the room lock. The row has already been fetched
     * through a scoped finder; making a {@code FOR UPDATE} conditional on authorization
     * would couple locking to permissions, which is the wrong dependency.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from HostelFee f where f.id = :id")
    Optional<HostelFee> findByIdForUpdate(@Param("id") Long id);

    @Query(value = """
            select f from HostelFee f
            join fetch f.student s
            join fetch s.user u
            where s.gender in :genders
            order by f.dueDate asc, f.id asc
            """,
            countQuery = """
                    select count(f) from HostelFee f
                    where f.student.gender in :genders
                    """)
    Page<HostelFee> findInScope(@Param("genders") Collection<Gender> genders, Pageable pageable);

    @Query(value = """
            select f from HostelFee f
            join fetch f.student s
            join fetch s.user u
            where s.gender in :genders and f.status = :status
            order by f.dueDate asc, f.id asc
            """,
            countQuery = """
                    select count(f) from HostelFee f
                    where f.student.gender in :genders and f.status = :status
                    """)
    Page<HostelFee> findInScopeByStatus(
            @Param("genders") Collection<Gender> genders,
            @Param("status") FeeStatus status,
            Pageable pageable);

    /** A student's own invoices. Uses {@code idx_hostel_fees_student}. */
    List<HostelFee> findByStudentIdOrderByDueDateDesc(Long studentId);

    /**
     * Invoices a reminder is due on, oldest due date first.
     *
     * <p>Fetch-joins the student and account because the reminder needs an address, and
     * a nightly job that lazily loads two rows per invoice is the N+1 that makes a
     * five-second job take four minutes.
     *
     * <p>Filtered on the same predicate as the partial index
     * {@code idx_hostel_fees_outstanding}, so the scan touches only unsettled rows
     * rather than the whole historical table. Paid and cancelled invoices are not in
     * that index at all.
     */
    @Query("""
            select f from HostelFee f
            join fetch f.student s
            join fetch s.user u
            where f.status in (com.hostelops.domain.FeeStatus.UNPAID,
                               com.hostelops.domain.FeeStatus.PARTIALLY_PAID)
              and f.dueDate <= :through
            order by f.dueDate asc, f.id asc
            """)
    List<HostelFee> findDueForReminder(@Param("through") LocalDate through);

    long countByStudentGenderInAndStatus(Collection<Gender> genders, FeeStatus status);

    /**
     * Billed and collected totals for the caller's students, per term.
     *
     * <p>The collection rate is {@code collected / billed}, and both halves are summed
     * in the database. Grouping by term rather than reporting one grand total because a
     * healthy rate on this semester's fees and a terrible one on last year's are the
     * same average and completely different problems.
     *
     * <p>Cancelled invoices are excluded from the denominator: money that was written
     * off is not money that failed to be collected, and counting it as uncollected makes
     * the rate drift down every time an invoice is voided.
     */
    @Query("""
            select new com.hostelops.repository.FeeCollectionTotals(
                f.academicYear,
                f.semester,
                count(f),
                sum(f.amountPaise),
                sum(f.amountPaidPaise),
                count(case when f.status = com.hostelops.domain.FeeStatus.PAID then 1 end),
                count(case when f.dueDate < :today
                            and f.status in (com.hostelops.domain.FeeStatus.UNPAID,
                                             com.hostelops.domain.FeeStatus.PARTIALLY_PAID)
                           then 1 end))
            from HostelFee f
            where f.student.gender in :genders
              and f.status <> com.hostelops.domain.FeeStatus.CANCELLED
            group by f.academicYear, f.semester
            order by f.academicYear desc, f.semester desc
            """)
    List<FeeCollectionTotals> findCollectionTotals(
            @Param("genders") Collection<Gender> genders, @Param("today") LocalDate today);
}
