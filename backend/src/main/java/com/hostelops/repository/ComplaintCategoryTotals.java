package com.hostelops.repository;

import com.hostelops.domain.ComplaintCategory;
import com.hostelops.domain.ComplaintStatus;

/**
 * One cell of the category-by-status grid.
 *
 * <p>Returned sparsely: combinations with no complaints are absent rather than zero,
 * because a group-by has nothing to group. The service fills the gaps, which keeps the
 * query from having to generate the full cross product.
 */
public record ComplaintCategoryTotals(
        ComplaintCategory category, ComplaintStatus status, long count) {}
