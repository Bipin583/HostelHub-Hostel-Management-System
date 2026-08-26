package com.hostelops.dto.dashboard;

/**
 * The warden's landing tiles.
 *
 * <p>Four counts, each the answer to "is there anything waiting for me": invoices nobody
 * has paid against, complaints nobody has picked up, absence alerts nobody has
 * acknowledged, and how much this warden has posted to the notice board.
 *
 * <p>Counts, not lists. Every one of these numbers has a route that returns the rows behind
 * it, so a tile carrying its own page of data would be a second way to read the same query
 * -- and the two would drift the moment either changed. A dashboard says how many; the list
 * routes say which.
 *
 * <p>{@code long} rather than {@code int} throughout, matching the repositories' return
 * type. Nothing here will overflow an {@code int} in a hostel, but narrowing at the DTO
 * boundary would put a cast in the mapper whose only purpose is to be wrong eventually.
 *
 * <p>Each figure is already scoped to the caller's hostels by the service that produces it,
 * so this record needs no gender or hostel field: two wardens hitting the same endpoint get
 * different numbers, and neither can widen the query by asking differently.
 */
public record DashboardResponse(
        long unpaidFees, long openComplaints, long openAbsenceAlerts, long noticesPosted) {
}
