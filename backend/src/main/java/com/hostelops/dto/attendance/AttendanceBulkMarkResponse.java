package com.hostelops.dto.attendance;

import java.time.LocalDate;
import java.util.List;

/**
 * What a bulk register submission actually did.
 *
 * <p>Split into created and updated rather than returning one count, because
 * re-submitting a register is a normal correction and the warden needs to see that
 * the second submission changed 3 rows rather than 200. A single "200 marks saved"
 * is true and tells them nothing.
 *
 * <p>{@code skippedStudentIds} carries the ids that were not in the caller's scope
 * or do not exist. Reporting them rather than failing the whole batch is deliberate:
 * a register submitted from a stale roster should record the students it can, and a
 * 404 for the whole request would lose 199 good marks over one transferred student.
 */
public record AttendanceBulkMarkResponse(
        LocalDate attendanceDate,
        int created,
        int updated,
        List<Long> skippedStudentIds) {
}
