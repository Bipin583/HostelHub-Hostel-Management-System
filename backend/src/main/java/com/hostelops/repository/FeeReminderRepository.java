package com.hostelops.repository;

import com.hostelops.domain.FeeReminder;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The reminder log, which is also the reminder job's lock.
 *
 * <p>There is no "mark job as run" method here on purpose. Idempotency is per invoice
 * per day, expressed as {@code uq_fee_reminders_per_day}, so nothing in the job needs
 * to know whether it has run before -- only whether this particular invoice has been
 * reminded today, and the database answers that.
 */
public interface FeeReminderRepository extends Repository<FeeReminder, Long> {

    FeeReminder save(FeeReminder reminder);

    /**
     * Writes the row and flushes immediately.
     *
     * <p>The flush is the point. It moves the unique-constraint check to this call, so a
     * reminder already sent today fails here -- inside the per-invoice transaction, where
     * the job can catch it and skip -- instead of at the end of the batch, where a single
     * duplicate would roll back every reminder in it.
     */
    FeeReminder saveAndFlush(FeeReminder reminder);

    boolean existsByFeeIdAndReminderDate(Long feeId, LocalDate reminderDate);

    /**
     * Which of these invoices have already been reminded today.
     *
     * <p>One query for the whole batch instead of an {@code exists} per invoice. The job
     * uses this to skip cheaply; correctness still rests on the constraint at insert
     * time, because another instance can insert between this read and the write.
     */
    @Query("""
            select r.fee.id from FeeReminder r
            where r.reminderDate = :date and r.fee.id in :feeIds
            """)
    List<Long> findFeeIdsRemindedOn(
            @Param("date") LocalDate date, @Param("feeIds") Collection<Long> feeIds);

    long countByReminderDate(LocalDate reminderDate);

    long countByFeeId(Long feeId);
}
