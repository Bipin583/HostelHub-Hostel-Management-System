package com.hostelops.notify;

/**
 * One message to one person.
 *
 * <p>Deliberately not a template name plus a model map. Templating is a second language
 * to debug, and the two messages this application sends -- a fee reminder and an absence
 * notice -- are composed by the services that know what they mean. If a third and fourth
 * arrive, this record is what a template engine would render into anyway.
 *
 * <p>{@code recipient} is an email address today because {@link Notifier} has one
 * implementation and it writes to a log. The field is not called {@code emailAddress} for
 * that reason: {@code ReminderChannel} already anticipates SMS, and the channel is the
 * sender's business rather than the message's.
 */
public record Notification(String recipient, String subject, String body) {
}
