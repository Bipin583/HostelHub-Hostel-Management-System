package com.hostelops.dto.fee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Raising an invoice against a student.
 *
 * <p>{@code amountPaise} is an integer count of paise, and the field name says so.
 * The predecessor accepted a decimal "amount" and stored it as a float, which is how a
 * ledger acquires rows off by a hundredth of a rupee. Naming the unit in the field
 * makes a client that sends 500 for "five hundred rupees" fail its own review rather
 * than silently under-bill by a factor of a hundred.
 *
 * <p>{@code @Positive} rather than {@code @Min(0)}: a zero-rupee invoice is not a
 * waiver, it is a mistake, and {@code ck_hostel_fees_amount} already says {@code > 0}.
 * The annotation exists so the caller gets a 400 naming the field instead of a
 * constraint-violation 409 naming a database object.
 */
public record FeeCreateRequest(
        @NotNull Long studentId,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 20) String academicYear,
        @NotBlank @Size(max = 20) String semester,
        @NotNull @Positive Long amountPaise,
        @NotNull LocalDate dueDate,
        @Size(max = 5000) String description) {
}
