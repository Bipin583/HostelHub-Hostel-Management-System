package com.hostelops.domain;

/**
 * The complaint's lifecycle.
 *
 * <p>Three states rather than a boolean, because the interesting number is not
 * how many complaints are open -- it is how long they take. {@code IN_PROGRESS}
 * splits that duration into the part nobody had looked at it yet and the part
 * somebody was working on it, and those two numbers have different causes and
 * different fixes. A resolved/unresolved flag can only report the sum.
 *
 * <p>Transitions are checked in {@code Complaint.transitionTo}: forward only, and
 * never out of {@code RESOLVED}. Reopening is a new complaint, which keeps the
 * resolution timestamps of the original honest.
 */
public enum ComplaintStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED;

    /**
     * Whether {@code next} is a legal move from this state.
     *
     * <p>Lives on the enum rather than in the service so every caller -- the
     * warden endpoint, a future bulk tool, a data migration -- answers the
     * question the same way.
     */
    public boolean canTransitionTo(ComplaintStatus next) {
        return switch (this) {
            case OPEN -> next == IN_PROGRESS || next == RESOLVED;
            // Skipping IN_PROGRESS is allowed: a warden who fixes a light bulb on
            // the way past should not have to click twice to say so.
            case IN_PROGRESS -> next == RESOLVED;
            case RESOLVED -> false;
        };
    }
}
