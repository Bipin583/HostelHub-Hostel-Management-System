package com.hostelops.dto.attendance;

import com.hostelops.domain.AttendanceStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Marks a whole register in one request.
 *
 * <p>This is how attendance is actually taken -- one warden, one date, a list of
 * students -- and making it one request rather than 200 means the whole roll call is
 * one transaction. A partial register is the failure mode worth designing against:
 * with per-student requests, a browser closed halfway through leaves a day where some
 * students are marked and the rest are indistinguishable from "not taken yet", which
 * is exactly the ambiguity the absence detector cannot tolerate.
 *
 * <p>The date lives on the envelope rather than on each entry, so a bulk submission
 * cannot straddle two days.
 *
 * <p>{@code @Size} caps the batch. Without it one request can pin a connection for as
 * long as it likes, and a hostel register is a few hundred rows -- ten thousand is a
 * client bug or an attack, and either way should be a 400 rather than a slow 200.
 */
public record AttendanceBulkMarkRequest(
        @NotNull LocalDate attendanceDate,
        @NotEmpty @Size(max = 1000) @Valid List<Entry> marks) {

    public record Entry(
            @NotNull Long studentId,
            @NotNull AttendanceStatus status) {
    }
}
