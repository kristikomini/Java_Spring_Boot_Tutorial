package it.unicam.cs.enrollment.mail.transport;

/**
 * A message could not be handed over, plus the one fact the dispatcher needs in
 * order to decide what to do next: is trying again capable of helping?
 *
 * <h2>Transient versus permanent - the distinction that makes a queue work</h2>
 * <ul>
 *   <li>TRANSIENT: connection refused, timeout, "4xx try again later", the relay
 *       is rebooting. Nothing about the message is wrong. Retrying is exactly
 *       the right response, and after a backoff it usually succeeds.</li>
 *   <li>PERMANENT: the address does not parse, the mailbox does not exist,
 *       "5xx no such user". Retrying cannot change any of that. Every attempt
 *       costs a connection, delays the rest of the queue, and postpones the
 *       moment a human learns the address is wrong.</li>
 * </ul>
 *
 * <p>Systems that fail to make this distinction fail in one of two visible
 * ways: they retry everything (and a single bad address occupies the dispatcher
 * forever) or they retry nothing (and a thirty-second network blip loses a day's
 * mail). It is the same 4xx/5xx split HTTP and SMTP both encode in their status
 * codes, for the same reason.
 *
 * <h2>Why it is a checked exception</h2>
 * Most of this codebase throws unchecked exceptions, and that is usually right:
 * a caller can rarely do anything useful about a business rule violation except
 * let it propagate. Here the caller can, and must, do something specific - mark
 * the row, count the attempt, schedule the retry. Making the compiler insist on
 * that is the case checked exceptions were designed for.
 */
public class MailDeliveryException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean permanent;

    public MailDeliveryException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    public MailDeliveryException(String message, boolean permanent) {
        this(message, permanent, null);
    }

    /** Will not be retried: nothing about waiting longer would change the outcome. */
    public static MailDeliveryException permanent(String message, Throwable cause) {
        return new MailDeliveryException(message, true, cause);
    }

    /** Will be retried, until the retry budget is spent. */
    public static MailDeliveryException transientFailure(String message, Throwable cause) {
        return new MailDeliveryException(message, false, cause);
    }

    public boolean isPermanent() {
        return permanent;
    }
}
