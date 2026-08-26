package com.hostelops.domain;

/**
 * What the complaint is about, used for routing and for spotting patterns.
 *
 * <p>Categories exist so that "the third-floor bathroom" stops being three
 * unrelated tickets. The column is a plain constrained string in the schema, so
 * adding a value here is a code change and not a migration.
 */
public enum ComplaintCategory {
    ELECTRICAL,
    PLUMBING,
    FURNITURE,
    CLEANLINESS,
    INTERNET,
    FOOD,
    SECURITY,
    /** The escape hatch. Its share of the total is a signal that a category is missing. */
    GENERAL
}
