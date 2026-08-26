package com.hostelops.security;

import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.HostelType;
import com.hostelops.domain.Role;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * What rows the current caller is allowed to touch.
 *
 * <p>This is the one place that turns "who is asking" into "which rows", and it
 * is what replaces the predecessor's {@code get_hostel_filter_for_warden} /
 * {@code get_gender_filter_for_warden} helper pair. Those were functions each
 * view had to remember to call; forgetting one silently widened the query to
 * every hostel. Here the scope is expressed as a *set of permitted values* that
 * repository methods take as a required parameter, so a query that skipped the
 * filter would not compile -- there is no unfiltered finder to call.
 *
 * <p>An admin is not a special case in the calling code. An admin simply has the
 * full set: {@code visibleGenders()} returns every gender and
 * {@code visibleHostelTypes()} every hostel type. That is why services contain
 * no {@code if (isAdmin())} branches around their queries -- the same query runs
 * for both, with a wider or narrower {@code IN (...)} list.
 */
public record AccessScope(Long userId, Role role, HostelScope hostelScope, Long studentId) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean isWarden() {
        return role == Role.WARDEN;
    }

    public boolean isStudent() {
        return role == Role.STUDENT;
    }

    /**
     * Genders of students this caller may read. Admins see all; a warden sees
     * only the gender their hostel houses.
     */
    public Set<Gender> visibleGenders() {
        if (isWarden() && hostelScope != null) {
            return EnumSet.of(hostelScope.gender());
        }
        return EnumSet.allOf(Gender.class);
    }

    /** Hostels this caller may read rooms in. Admins see all three. */
    public Set<HostelType> visibleHostelTypes() {
        if (isWarden() && hostelScope != null) {
            return hostelScope.hostelTypes();
        }
        return EnumSet.allOf(HostelType.class);
    }

    /**
     * Guards a student row a caller reached by some route other than a scoped
     * query -- for instance one loaded via its allocation.
     *
     * <p>403 rather than 404 is a deliberate choice: the id space here is
     * sequential and non-secret, so hiding existence buys nothing, and a warden
     * who mistypes a URL is better served by "not your hostel" than by
     * "no such student".
     */
    public void requireVisible(Gender studentGender) {
        if (!visibleGenders().contains(studentGender)) {
            throw ApiException.outOfScope("student");
        }
    }

    public void requireVisible(HostelType hostelType) {
        if (!visibleHostelTypes().contains(hostelType)) {
            throw ApiException.outOfScope("room");
        }
    }

    /** For endpoints a student may only invoke on their own record. */
    public void requireSelf(Long targetStudentId) {
        if (isStudent() && !java.util.Objects.equals(studentId, targetStudentId)) {
            throw ApiException.outOfScope("student record");
        }
    }

    /**
     * The caller's own student id, for the routes that take no id at all.
     *
     * <p>403 rather than 404: the caller is authenticated and the route exists, they
     * simply are not a student. An admin or a warden reaching a student-portal
     * endpoint is the usual way to see this, and telling them "this endpoint is for
     * students" is more useful than pretending the route is absent.
     *
     * <p>Lives here rather than in each service so that "which student am I" has one
     * answer and one failure mode across the whole student portal.
     */
    public Long requireStudentId() {
        if (studentId == null) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "This endpoint is for student accounts",
                    Map.of("role", role.name()));
        }
        return studentId;
    }
}
