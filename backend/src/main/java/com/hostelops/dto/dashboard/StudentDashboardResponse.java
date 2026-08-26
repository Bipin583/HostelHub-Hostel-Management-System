package com.hostelops.dto.dashboard;

/**
 * The resident's landing tile.
 *
 * <p>One number today: how many complaints this student has filed that nobody has resolved
 * yet. It is a record rather than a bare {@code long} for the reason argued on
 * {@code FeeReminderDayCountResponse} -- a response body of {@code 2} cannot grow a second
 * field without breaking every client that parsed it as a scalar, and a student dashboard is
 * the surface most likely to grow one.
 *
 * <p>Deliberately not carrying a fee tile. The staff-side unpaid count is scoped by the
 * caller's visible genders, which for a student resolves to their own gender rather than
 * their own record -- so reusing it here would tell a resident how many of their peers owe
 * money. A student's own invoices come from {@code GET /api/v1/student/fees}, which pairs
 * the query with the JWT subject; a tile for it needs a count that does the same, and
 * inventing one belongs to the repository layer rather than to this DTO.
 */
public record StudentDashboardResponse(long unresolvedComplaints) {
}
