package com.hostelops.repository;

import com.hostelops.domain.AllocationStatus;

/**
 * The optional filters a student listing accepts.
 *
 * <p>Grouped into a record rather than added to the derived-query method names,
 * which is where this kind of interface usually goes wrong: three optional filters
 * spelled as derived queries is eight method names, and the eighth is the one that
 * forgets the scope argument.
 *
 * <p>The caller's permitted genders are deliberately <em>not</em> in here. They are
 * a separate, mandatory parameter on the search method, so they can never be left
 * null the way an optional filter can.
 */
public record StudentSearchCriteria(
        Integer yearOfStudy,
        AllocationStatus allocationStatus,
        String query) {

    public static final StudentSearchCriteria UNFILTERED = new StudentSearchCriteria(null, null, null);

    public StudentSearchCriteria {
        query = query == null || query.isBlank() ? null : query.trim();
    }
}
