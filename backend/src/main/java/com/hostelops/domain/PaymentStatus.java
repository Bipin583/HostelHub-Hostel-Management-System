package com.hostelops.domain;

/**
 * Where a single payment attempt got to.
 *
 * <p>Three terminal-ish states and no {@code CANCELLED}: a payment the student
 * abandoned is indistinguishable, from the server's side, from one still in flight
 * at the gateway. Rather than guess, the row stays {@code PENDING} until the
 * gateway says otherwise, and {@code ck_fee_payments_completed} enforces that only
 * the two settled states carry a {@code completed_at}.
 *
 * <p>A failed attempt is kept rather than deleted. "This card was declined three
 * times" is the answer to a support question that a cleaned-up table cannot give.
 */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED;

    /** Whether this state is final -- i.e. whether the row may still change. */
    public boolean isSettled() {
        return this != PENDING;
    }
}
