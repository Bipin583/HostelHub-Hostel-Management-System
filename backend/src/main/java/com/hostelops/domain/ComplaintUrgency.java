package com.hostelops.domain;

/**
 * How badly a complaint needs attention.
 *
 * <p>Set by the student who raises it, which is worth being explicit about: the
 * reporter's urgency is a claim, not a fact, and the warden's queue sorts by it
 * without treating it as ground truth. The alternative -- letting only staff set
 * urgency -- means the student with no running water has no way to say so.
 */
public enum ComplaintUrgency {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
