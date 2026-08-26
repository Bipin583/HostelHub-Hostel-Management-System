package com.hostelops.notify;

/**
 * Sends a message to a person outside the system.
 *
 * <p>An interface with one logging implementation, which is a claim worth being explicit
 * about: <b>this application does not send email.</b> It records what it would have sent,
 * where it would have gone, and -- through {@code fee_reminders} and
 * {@code absence_alerts.notified_at} -- that it decided to send it. Wiring SMTP in means
 * adding one bean; nothing else changes.
 *
 * <p>The alternative was to pull in {@code spring-boot-starter-mail} and configure a real
 * host. That would give the repository a feature nobody cloning it can run, since it needs
 * credentials to a mail server, and would make the reminder job's behaviour depend on
 * whether those credentials happen to work. The seam is the honest version: the
 * idempotency guarantee this project actually makes is about the <em>decision</em> to send
 * -- once per invoice per day, provably -- and that guarantee is testable without a mail
 * server, which is why {@code FeeReminderIdempotencyIT} can assert it.
 *
 * <p>Implementations must be thread-safe, and must throw {@link NotificationException}
 * rather than returning a boolean, so that a caller which forgets to check cannot silently
 * record a message as delivered.
 */
public interface Notifier {

    /**
     * Delivers one message.
     *
     * @throws NotificationException if it could not be delivered
     */
    void send(Notification notification);
}
