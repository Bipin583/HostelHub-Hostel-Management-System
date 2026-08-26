package com.hostelops.notify;

/**
 * Delivery failed.
 *
 * <p>A plain {@code RuntimeException} rather than an {@code ApiException}, for the same
 * reason {@code PaymentGatewayException} is: a sender has no business choosing an HTTP
 * status. Callers decide what a failed message means to them, and both of them --
 * {@code FeeReminderJob} and {@code AbsenceAlertService} -- decide it means "count it and
 * carry on", because a batch of five hundred reminders must not stop at the one address
 * that bounced.
 */
public class NotificationException extends RuntimeException {

    public NotificationException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotificationException(String message) {
        super(message);
    }
}
