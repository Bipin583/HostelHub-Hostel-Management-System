package com.hostelops.dto.complaint;

import com.hostelops.domain.ComplaintCategory;
import com.hostelops.domain.ComplaintStatus;
import java.util.List;
import java.util.Map;

/**
 * The complaints dashboard.
 *
 * <p>Three groups of numbers, and the reason all three are here rather than just the
 * average is that each one alone is misleading:
 *
 * <ul>
 *   <li>{@code resolution} covers only complaints that were <em>resolved</em>. A team
 *       that closes easy tickets and ignores hard ones has an excellent average.
 *   <li>{@code backlog} is what that average hides -- how many are still open and how
 *       old the oldest is.
 *   <li>{@code byCategory} is where the pattern lives: twelve plumbing complaints on
 *       one floor is a burst pipe, not twelve tickets.
 * </ul>
 *
 * <p>Durations are seconds, as {@code Double}, and null when nothing was resolved in
 * the window. Null rather than zero: "no complaints were resolved" and "they were
 * resolved instantly" are opposite facts and a zero would render them identically.
 */
public record ComplaintAnalyticsResponse(
        Resolution resolution,
        Backlog backlog,
        List<CategoryCount> byCategory) {

    /**
     * Time-to-resolution, split into the wait and the work.
     *
     * <p>Both halves are reported because they have different fixes: a long
     * {@code avgQueueSeconds} means nobody is triaging, a long {@code avgWorkSeconds}
     * means the repairs themselves are slow. A single total cannot distinguish them.
     *
     * <p>{@code p90TotalSeconds} sits next to the mean because one complaint left open
     * over the summer break drags an average past usefulness.
     */
    public record Resolution(
            long resolvedCount,
            Double avgTotalSeconds,
            Double avgQueueSeconds,
            Double avgWorkSeconds,
            Double p90TotalSeconds) {
    }

    public record Backlog(long openCount, double oldestOpenSeconds) {
    }

    /**
     * One category's complaint counts, broken down by status.
     *
     * <p>{@code byStatus} is dense -- every status present with an explicit zero --
     * even though the underlying group-by returns it sparsely. A chart that has to
     * guess whether a missing key means zero is a chart with a bug in it, and filling
     * the gaps once here is cheaper than every client doing it.
     */
    public record CategoryCount(
            ComplaintCategory category, long total, Map<ComplaintStatus, Long> byStatus) {
    }
}
