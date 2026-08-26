package com.hostelops.payment;

/**
 * What a gateway needs to open a checkout.
 *
 * <p>{@code receipt} is our own reference for the order -- the {@code fee_payments} row
 * id -- and is sent so that a settlement report downloaded from the provider's
 * dashboard can be reconciled against this database without a lookup table. Every
 * gateway worth using echoes it back.
 *
 * <p>No payer details. A gateway does not need the student's name or email to take a
 * card payment, and sending them would put personal data in a third party's logs for
 * no gain. The predecessor forwarded the full user object because the vendor's example
 * did.
 */
public record PaymentOrderRequest(long amountPaise, String currency, String receipt) {
}
