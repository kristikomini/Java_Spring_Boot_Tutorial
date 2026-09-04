package it.unicam.cs.enrollment.mail.transport;

import it.unicam.cs.enrollment.mail.domain.MailMessage;

/**
 * The one thing the mail subsystem needs from the outside world: "hand this
 * message to something that will deliver it".
 *
 * <h2>Why an interface with two implementations, and not just SMTP</h2>
 * This is a PORT, in the hexagonal-architecture sense: the application declares
 * what it needs, and an adapter supplies it. The payoff is not theoretical.
 *
 * <ul>
 *   <li>{@code LoggingMailTransport} lets the whole pipeline - queue, schedule,
 *       retry, purge - run on a laptop with no SMTP server anywhere, and lets
 *       a reader SEE the rendered email in {@code docker compose logs}.</li>
 *   <li>{@code SmtpMailTransport} is the real one.</li>
 *   <li>A test supplies a lambda that counts calls, or throws on demand, and
 *       every retry rule becomes testable in milliseconds.</li>
 * </ul>
 *
 * <p>Swapping the provider later - SendGrid, Amazon SES, a corporate relay -
 * means one new class implementing this interface. Nothing above it changes,
 * because nothing above it ever knew how mail leaves the building.
 *
 * <h2>What an implementation must guarantee</h2>
 * <ul>
 *   <li>Return normally only if the message was ACCEPTED by the far end.
 *       "Accepted" is not "read", and is not even "delivered" - SMTP is a chain
 *       of hops and the next one can still bounce it. It is nonetheless the
 *       strongest fact this process can ever know.</li>
 *   <li>Throw {@link MailDeliveryException} otherwise, saying whether the
 *       failure is worth retrying. That single boolean is what separates a
 *       queue that drains from one that spins.</li>
 *   <li>Be thread-safe. One instance is shared by the whole application.</li>
 * </ul>
 */
public interface MailTransport {

    /**
     * Hand one message over for delivery.
     *
     * @param message a fully rendered message; the transport adds only envelope
     *                details (from-address, headers), never content
     * @throws MailDeliveryException if the far end did not accept it
     */
    void send(MailMessage message) throws MailDeliveryException;

    /**
     * A short human description - "SMTP via java:jboss/mail/Enrollment", "log
     * only" - shown by the mailbox API.
     *
     * <p>Worth having because the most common mail incident is not "delivery
     * failed", it is "nothing arrived and the log says everything is fine",
     * which is exactly what a log-only transport looks like when someone
     * believed it was configured for SMTP.
     */
    String describe();
}
