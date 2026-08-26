package com.hostelops.domain;

/**
 * Whether a student was present on a given day.
 *
 * <p>Only two values, and deliberately no {@code LEAVE} or {@code EXCUSED}. Both
 * would be honest additions to the domain, and both would immediately raise the
 * question the absence detector has to answer: does approved leave break an
 * absence streak? That is a policy decision with a real answer either way, and
 * encoding it as a third enum value would settle it silently. Leave is instead a
 * calendar concern -- see {@code AttendanceProperties.holidays} -- which keeps the
 * streak rule reading over working days only.
 */
public enum AttendanceStatus {
    PRESENT,
    ABSENT
}
