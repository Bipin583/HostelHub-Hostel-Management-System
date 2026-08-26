package com.hostelops.dto.room;

import com.hostelops.domain.HostelType;

/**
 * Occupancy for one hostel block.
 *
 * <p>{@code freeBeds} and {@code occupancyPercent} are derived, and derived values
 * in a response usually mean two clients disagreeing about a rounding rule. They
 * are here on purpose: the alternative is every caller -- the dashboard, the
 * warden's block view, a future export -- repeating the arithmetic, and one of
 * them getting it wrong on a block with zero rooms.
 *
 * @param occupancyPercent occupied beds as a percentage of total, one decimal
 *                         place, and 0 rather than NaN for a block with no beds
 */
public record OccupancyResponse(
        HostelType hostelType,
        String block,
        long roomCount,
        long totalBeds,
        long occupiedBeds,
        long freeBeds,
        double occupancyPercent) {
}
