package com.hostelops.dto.fee;

import com.hostelops.domain.FeeStatus;
import com.hostelops.dto.student.StudentSummaryResponse;
import java.time.LocalDate;

/**
 * An invoice as the client sees it.
 *
 * <p>{@code outstandingPaise} is sent even though it is {@code amountPaise -
 * amountPaidPaise}, because it is the number the "Pay now" button uses and a client
 * that recomputes it is a client that can disagree with the server about what is owed.
 * The server's arithmetic is the only arithmetic that matters here.
 *
 * <p>{@code overdue} is likewise computed server-side, against the server's date. A
 * device with a wrong clock must not be able to decide that a fee is not late yet.
 */
public record FeeResponse(
        Long id,
        StudentSummaryResponse student,
        String title,
        String academicYear,
        String semester,
        Long amountPaise,
        Long amountPaidPaise,
        Long outstandingPaise,
        LocalDate dueDate,
        String description,
        FeeStatus status,
        boolean overdue) {
}
