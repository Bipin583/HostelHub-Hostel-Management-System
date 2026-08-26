package com.hostelops.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The slice of the estate a warden governs.
 *
 * <p>This enum is the single definition of the containment rule. The
 * predecessor spread the same knowledge across two helper functions
 * ({@code get_hostel_filter_for_warden} and {@code get_gender_filter_for_warden})
 * that every view had to remember to call, which meant a new endpoint was one
 * forgotten call away from leaking another hostel's students. Here the mapping
 * lives in one place and the scoped repository queries take it as a parameter,
 * so there is nothing to forget.
 */
public enum HostelScope {

    /** Ladies' hostel warden: female students, LH rooms. */
    LH(Gender.F, EnumSet.of(HostelType.LH)),

    /** Men's hostel warden: male students, both BH and MH rooms. */
    MH(Gender.M, EnumSet.of(HostelType.BH, HostelType.MH));

    private final Gender gender;
    private final Set<HostelType> hostelTypes;

    HostelScope(Gender gender, Set<HostelType> hostelTypes) {
        this.gender = gender;
        this.hostelTypes = Collections.unmodifiableSet(hostelTypes);
    }

    /** The only student gender this warden may see. */
    public Gender gender() {
        return gender;
    }

    /** The hostels whose rooms this warden may see. */
    public Set<HostelType> hostelTypes() {
        return hostelTypes;
    }

    public boolean covers(HostelType hostelType) {
        return hostelTypes.contains(hostelType);
    }

    public boolean covers(Gender studentGender) {
        return gender == studentGender;
    }

    /** The scope that governs a given hostel, e.g. BH -> MH warden. */
    public static HostelScope forHostel(HostelType hostelType) {
        for (HostelScope scope : values()) {
            if (scope.covers(hostelType)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("No scope governs hostel " + hostelType);
    }
}
