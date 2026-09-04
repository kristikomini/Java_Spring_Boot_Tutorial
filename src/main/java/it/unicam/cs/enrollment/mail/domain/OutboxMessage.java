package it.unicam.cs.enrollment.mail.domain;

import it.unicam.cs.enrollment.domain.model.BaseEntity;
import it.unicam.cs.enrollment.domain.model.Email;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Objects;

/**
 * A row in the mail outbox: one email that has been promised to someone.
 *
 * <h2>The transactional outbox, in one paragraph</h2>
 * The enrollment is written to the database and the email is written to the
 * database, in the SAME transaction. Either both facts exist or neither does -
 * which is the guarantee you cannot get by calling an SMTP server from inside
 * business logic, because a database can roll back and a delivered email cannot.
 * A separate process ({@code MailDispatcher}) reads these rows afterwards and
 * does the part that talks to the outside world.
 *
 * <p>The same shape solves the same problem for HTTP webhooks, for publishing to
 * a message broker, and for anything else where "commit locally" and "tell the
 * outside world" must not drift apart. It is worth recognising by name.
 *
 * <h2>Reading the columns</h2>
 * <ul>
 *   <li>{@code status / attempts / next_attempt_at} - the scheduling state. The
 *       dispatcher's query is "PENDING and due", so those are the columns with
 *       an index on them.</li>
 *   <li>{@code dedupe_key} - a UNIQUE constraint, which is what actually makes
 *       double-queuing impossible. An {@code if (exists) return;} in Java is a
 *       race between two threads; a unique index is a decision made by the one
 *       component that sees every writer.</li>
 *   <li>{@code last_error} - why the previous attempt failed, kept on the row
 *       rather than only in the log, so the mailbox API can show it without
 *       anyone needing shell access to a server.</li>
 * </ul>
 *
 * <h2>Why the body is copied in, rather than re-rendered at send time</h2>
 * The template could be re-applied when the dispatcher runs, saving a column.
 * But then the email a student receives depends on what the template file said
 * at DELIVERY time, and on data that may have changed in between - a message
 * that sat in a retry loop across a deploy would go out worded differently from
 * one sent immediately. Rendering once, at the moment of the fact, makes the row
 * a faithful record of what was promised.
 */
@Entity
@Table(
        name = "mail_outbox",
        uniqueConstraints = {
                // Nullable columns are exempt from a UNIQUE constraint in SQL, so
                // messages with no dedupe key (a hand-sent test, say) coexist
                // happily - only the ones that opted in are constrained.
                @UniqueConstraint(name = "uk_mail_outbox_dedupe_key", columnNames = "dedupe_key")
        },
        indexes = {
                // The dispatcher's hot query, in the order it filters: status
                // first - PENDING is the tiny slice of a mostly-SENT table -
                // then the due time.
                @Index(name = "idx_mail_outbox_due", columnList = "status, next_attempt_at"),
                @Index(name = "idx_mail_outbox_recipient", columnList = "recipient")
        }
)
public class OutboxMessage extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int MAX_SUBJECT = 255;
    public static final int MAX_BODY = 20_000;
    public static final int MAX_ERROR = 500;

    /** Appended when a value had to be cut down to fit its column. */
    private static final String ELLIPSIS = "...";

    /**
     * The address, reusing the domain's {@link Email} value object rather than
     * declaring another String.
     *
     * <p>{@code @AttributeOverride} is needed because {@code Email} hard-codes
     * its column name as {@code email}, and here the column is {@code recipient}.
     * Overriding the name at the point of embedding is exactly what the
     * annotation is for - an embeddable should not have to know about every
     * table it will ever be embedded in.
     */
    @Embedded
    @NotNull
    @AttributeOverride(name = "value",
            column = @Column(name = "recipient", nullable = false, length = 255))
    private Email recipient;

    @Size(max = 120)
    @Column(name = "recipient_name", length = 120)
    private String recipientName;

    @NotBlank
    @Size(max = MAX_SUBJECT)
    @Column(name = "subject", nullable = false, length = MAX_SUBJECT)
    private String subject;

    /** {@code @Lob} so PostgreSQL gets {@code text} rather than a length limit. */
    @Lob
    @NotBlank
    @Size(max = MAX_BODY)
    @Column(name = "body", nullable = false)
    private String body;

    @Size(max = 60)
    @Column(name = "template_key", length = 60)
    private String templateKey;

    @Size(max = 120)
    @Column(name = "dedupe_key", length = 120, updatable = false)
    private String dedupeKey;

    /**
     * {@code EnumType.STRING}, never {@code ORDINAL}.
     *
     * <p>Ordinal stores the enum's position, so inserting a constant in the
     * middle of the declaration silently reinterprets every existing row -
     * yesterday's SENT becomes today's DEAD. The string costs a few bytes and is
     * readable in a {@code psql} session, which is worth far more.
     */
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "status", nullable = false, length = 20)
    private MailStatus status = MailStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * The earliest moment a dispatcher may pick this up. Set on creation
     * (usually "now", so the next sweep takes it) and pushed into the future by
     * each failure.
     *
     * <p>Storing the DUE TIME rather than "seconds to wait" means the
     * dispatcher's query is a plain comparison against the clock - no
     * arithmetic, no need to know when the row was last touched, and an index
     * can serve it.
     */
    @NotNull
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Size(max = MAX_ERROR)
    @Column(name = "last_error", length = MAX_ERROR)
    private String lastError;

    /**
     * The request that caused this email, copied from the log context by the
     * listener. When a student writes in to say the confirmation never arrived,
     * this is what turns "search the logs for that afternoon" into one grep.
     */
    @Size(max = 60)
    @Column(name = "correlation_id", length = 60)
    private String correlationId;

    /** Required by JPA. */
    protected OutboxMessage() {
    }

    /**
     * The only way application code creates one: from a rendered message, due
     * immediately.
     *
     * <p>A static factory rather than a public constructor, matching the rest of
     * the domain model - it can be named, it validates, and it keeps the
     * half-built state JPA needs out of reach of callers.
     */
    public static OutboxMessage queue(MailMessage message, Instant now) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(now, "now must not be null");

        OutboxMessage row = new OutboxMessage();
        row.recipient = message.getRecipient();
        row.recipientName = truncate(message.getRecipientName().orElse(null), 120);
        row.subject = truncate(message.getSubject(), MAX_SUBJECT);
        row.body = message.getBody();
        row.templateKey = message.getTemplateKey().orElse(null);
        row.dedupeKey = message.getDedupeKey().orElse(null);
        row.status = MailStatus.PENDING;
        row.attempts = 0;
        row.nextAttemptAt = now;
        return row;
    }

    /** The value view of this row, for code that must not touch the entity. */
    public MailMessage toMailMessage() {
        return MailMessage.to(recipient)
                .named(recipientName)
                .subject(subject)
                .body(body)
                .fromTemplate(templateKey)
                .dedupeKey(dedupeKey)
                .build();
    }

    // ------------------------------------------------------------------
    // State transitions
    //
    // Every one of them is a method on the entity, and every one refuses an
    // illegal move. The alternative - services calling setStatus() - puts the
    // rules in whichever service remembered them, and the next caller gets them
    // wrong. An entity that cannot be put into a nonsensical state is the
    // single highest-value habit in domain modelling.
    // ------------------------------------------------------------------

    /**
     * PENDING to SENDING. Called inside a short transaction that commits before
     * the network call, so a crash leaves visible evidence of an in-flight
     * attempt rather than a row that looks untouched.
     */
    public void markSending(Instant now) {
        requireStatus(MailStatus.PENDING, "claim");
        this.status = MailStatus.SENDING;
        this.claimedAt = now;
        this.attempts++;
    }

    /** SENDING to SENT. */
    public void markSent(Instant now) {
        requireStatus(MailStatus.SENDING, "mark sent");
        this.status = MailStatus.SENT;
        this.sentAt = now;
        this.claimedAt = null;
        this.lastError = null;
    }

    /**
     * SENDING to PENDING (try again later) or DEAD (give up) - the one decision
     * in this class with real judgement in it.
     *
     * <p>{@code permanent} comes from the transport: a rejected recipient is
     * permanent, a refused connection is not. Retrying a permanent failure burns
     * the budget for no reason and delays the moment a human finds out.
     */
    public void markFailed(String error, boolean permanent, RetryPolicy policy, Instant now) {
        requireStatus(MailStatus.SENDING, "mark failed");
        this.lastError = truncate(error, MAX_ERROR);
        this.claimedAt = null;

        if (permanent || policy.isExhausted(attempts)) {
            this.status = MailStatus.DEAD;
        } else {
            this.status = MailStatus.PENDING;
            this.nextAttemptAt = policy.nextAttemptAt(attempts, now);
        }
    }

    /**
     * SENDING to PENDING for a row whose dispatcher died: no outcome is known,
     * so it is simply made available again.
     *
     * <p>Note what this does NOT do: it does not decrement {@code attempts}. The
     * attempt genuinely happened and may well have delivered the mail. Counting
     * it stops a message that reliably kills its dispatcher - a body that
     * triggers a bug, say - from being retried forever.
     */
    public void releaseStuckClaim(Instant now) {
        requireStatus(MailStatus.SENDING, "release");
        this.status = MailStatus.PENDING;
        this.claimedAt = null;
        this.nextAttemptAt = now;
        this.lastError = "Dispatcher did not report an outcome; re-queued by the recovery sweep";
    }

    /** DEAD or CANCELLED back to PENDING, by a human decision, with a fresh budget. */
    public void requeue(Instant now) {
        if (status != MailStatus.DEAD && status != MailStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Only a dead or cancelled message can be requeued, but this one is " + status);
        }
        this.status = MailStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.claimedAt = null;
    }

    /** PENDING to CANCELLED, by a human decision. */
    public void cancel() {
        requireStatus(MailStatus.PENDING, "cancel");
        this.status = MailStatus.CANCELLED;
    }

    private void requireStatus(MailStatus expected, String action) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    "Cannot " + action + " a message in status " + status + "; expected " + expected);
        }
    }

    /** True when a dispatcher running at {@code now} should pick this row up. */
    public boolean isDue(Instant now) {
        return status == MailStatus.PENDING && !nextAttemptAt.isAfter(now);
    }

    /**
     * True when this row was claimed so long ago that the dispatcher holding it
     * must be gone.
     */
    public boolean isStuck(Instant now, java.time.Duration stuckAfter) {
        return status == MailStatus.SENDING
                && claimedAt != null
                && claimedAt.plus(stuckAfter).isBefore(now);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - ELLIPSIS.length()) + ELLIPSIS;
    }

    // ------------------------------------------------------------------
    // Accessors. No setters: every change goes through a transition above.
    // ------------------------------------------------------------------

    public Email getRecipient() {
        return recipient;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public MailStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = truncate(correlationId, 60);
    }

    @Override
    public String toString() {
        return "OutboxMessage{id=" + getId()
                + ", to=" + (recipient != null ? recipient.getValue() : null)
                + ", status=" + status
                + ", attempts=" + attempts + '}';
    }
}
