package com.hostelops.domain;

/**
 * The kind of change an audit event records.
 *
 * <p>Three verbs, matching {@code ck_audit_action}, and deliberately not a free
 * string like {@code "APPROVED"} or {@code "PAYMENT_CAPTURED"}. Domain-specific
 * verbs read better in a single log line and are useless in aggregate: "how many
 * times was this allocation touched, and by whom" is answerable over three values
 * and not over the forty that accumulate once every feature names its own.
 *
 * <p>What actually happened is in {@code payload_diff}. The action says which
 * shape that diff has -- {@code CREATE} carries only new values, {@code DELETE}
 * only old ones, {@code UPDATE} both.
 */
public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE
}
