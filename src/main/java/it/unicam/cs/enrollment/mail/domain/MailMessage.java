package it.unicam.cs.enrollment.mail.domain;

import it.unicam.cs.enrollment.domain.model.Email;

import java.util.Objects;
import java.util.Optional;

/**
 * One email, fully rendered and ready to be queued: who it goes to, what it
 * says, and how to recognise it if we are asked to send it twice.
 *
 * <h2>Why this type exists next to {@code OutboxMessage}</h2>
 * They hold almost the same fields, and merging them is the obvious-looking
 * simplification. Resist it. {@link OutboxMessage} is a JPA entity: it has an
 * id, a version, a row, a lifecycle and a persistence context. This class is a
 * VALUE - it can be built in a unit test with no database anywhere, passed to a
 * transport, compared by content, and thrown away.
 *
 * <p>The practical payoff shows up in {@code MailDispatcher}: it loads a row,
 * converts it to one of these, commits the transaction, and only then talks to
 * the network. Handing a detached JPA entity to code that runs after the
 * persistence context closed is how {@code LazyInitializationException} gets
 * into a stack trace at 2am. A value object cannot do that to you.
 *
 * <h2>The builder</h2>
 * Six constructor parameters of which four are Strings is a bug waiting for a
 * refactor to swap two of them. A builder makes every argument named at the
 * call site, and lets the optional parts genuinely be optional.
 */
public final class MailMessage {

    private final Email recipient;
    private final String recipientName;
    private final String subject;
    private final String body;
    private final String templateKey;
    private final String dedupeKey;

    private MailMessage(Builder builder) {
        this.recipient = Objects.requireNonNull(builder.recipient, "recipient must not be null");
        this.recipientName = builder.recipientName;
        this.subject = requireText(builder.subject, "subject");
        this.body = requireText(builder.body, "body");
        this.templateKey = builder.templateKey;
        this.dedupeKey = builder.dedupeKey;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public static Builder to(Email recipient) {
        return new Builder(recipient);
    }

    /** Convenience for callers holding a raw address; validates and normalises it. */
    public static Builder to(String recipient) {
        return new Builder(Email.of(recipient));
    }

    public Email getRecipient() {
        return recipient;
    }

    /** The human name, when we know it: {@code "Mario Rossi <mario@...>"}. */
    public Optional<String> getRecipientName() {
        return Optional.ofNullable(recipientName);
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    /** Which template produced this, for reporting. Never used for routing. */
    public Optional<String> getTemplateKey() {
        return Optional.ofNullable(templateKey);
    }

    /**
     * The IDEMPOTENCY KEY: a caller-chosen string that means "this exact email,
     * for this exact reason". {@code MailService} refuses to queue a second row
     * with the same key.
     *
     * <p>It is what makes {@code enqueue} safe to call from a retried
     * transaction. Without it, a service method that fails after queuing and is
     * then retried by the caller sends the student two identical confirmations -
     * and the second one arrives looking exactly as legitimate as the first.
     *
     * <p>Good keys are derived from the domain fact, not from the clock:
     * {@code enrollment-confirmed:4711} is right, {@code mail-2026-09-04T10:15}
     * is a key that never repeats and therefore prevents nothing.
     */
    public Optional<String> getDedupeKey() {
        return Optional.ofNullable(dedupeKey);
    }

    /**
     * The address as SMTP wants it. Deliberately strips the characters that
     * would let a display name break out of the header - see the note on header
     * injection in {@code SmtpMailTransport}.
     */
    public String formattedRecipient() {
        if (recipientName == null || recipientName.trim().isEmpty()) {
            return recipient.getValue();
        }
        String safeName = recipientName.replaceAll("[\r\n<>\"]", " ").trim();
        return safeName + " <" + recipient.getValue() + ">";
    }

    @Override
    public String toString() {
        // NEVER the body: mail bodies carry names, grades and addresses, and a
        // log line is copied into a ticket far more often than a database row is.
        return "MailMessage{to=" + recipient.getValue()
                + ", subject='" + subject + '\''
                + ", template=" + templateKey + '}';
    }

    /** Step-by-step construction; see the class javadoc for why. */
    public static final class Builder {

        private final Email recipient;
        private String recipientName;
        private String subject;
        private String body;
        private String templateKey;
        private String dedupeKey;

        private Builder(Email recipient) {
            this.recipient = recipient;
        }

        public Builder named(String recipientName) {
            this.recipientName = recipientName;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder fromTemplate(String templateKey) {
            this.templateKey = templateKey;
            return this;
        }

        public Builder dedupeKey(String dedupeKey) {
            this.dedupeKey = dedupeKey;
            return this;
        }

        public MailMessage build() {
            return new MailMessage(this);
        }
    }
}
