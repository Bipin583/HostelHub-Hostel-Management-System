package com.hostelops.dto.attendance;

import java.time.LocalDate;

/**
 * What one run of the absence scan did.
 *
 * <p>Returned from the manual trigger and logged by the scheduled job, and the reason
 * it separates {@code raised} from {@code extended} is that those two numbers are how
 * idempotency is observed from outside: running the scan twice on the same data must
 * report the same alerts extended and none raised. {@code AbsenceScanIdempotencyIT}
 * asserts exactly that.
 */
public record AbsenceScanResultResponse(
        LocalDate scanDate,
        int studentsExamined,
        int raised,
        int extended,
        int closed) {
}
