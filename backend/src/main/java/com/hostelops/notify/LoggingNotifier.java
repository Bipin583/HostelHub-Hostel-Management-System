package com.hostelops.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes messages to the log instead of delivering them.
 *
 * <p>The only {@link Notifier} in the application. See that interface for why.
 *
 * <p>Logs at INFO with the recipient and subject, and the body at DEBUG. Splitting them
 * that way keeps the operational line -- who was contacted about what, which is what an
 * office asks -- readable in a production log, without printing a paragraph per student
 * for a five-hundred-invoice reminder run.
 *
 * <p>A blank recipient throws rather than logging a message addressed to nobody. That is
 * the case a real SMTP client would reject too, and both callers treat the exception as
 * "this one could not be delivered, count it and continue" -- so a student with no email
 * on file costs one line in the failed tally rather than silently appearing to have been
 * told.
 */
@Component
public class LoggingNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

    @Override
    public void send(Notification notification) {
        if (notification.recipient() == null || notification.recipient().isBlank()) {
            throw new NotificationException("No recipient address for: " + notification.subject());
        }
        log.info("NOTIFY to={} subject={}", notification.recipient(), notification.subject());
        log.debug("NOTIFY body to={}: {}", notification.recipient(), notification.body());
    }
}
