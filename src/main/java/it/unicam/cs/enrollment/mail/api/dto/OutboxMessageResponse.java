package it.unicam.cs.enrollment.mail.api.dto;

import it.unicam.cs.enrollment.mail.domain.OutboxMessage;

import java.time.Instant;

/**
 * One outbox row, as JSON.
 *
 * <h2>Why the entity is not returned directly</h2>
 * The same argument as everywhere else in this API, plus one that is specific
 * to mail. Returning the entity would serialise whatever fields it happens to
 * have today, so adding an internal column silently changes a public contract;
 * and it would drag a lazily-loaded association into the JSON writer long after
 * the persistence context closed. A DTO is a deliberate, stable, reviewable
 * shape.
 *
 * <p>The mail-specific part is {@code body}. It is included here, in the
 * single-message view, because reading what was actually sent is the whole
 * reason an operator opens one - and omitted from the list view, because a page
 * of twenty full email bodies is a large response nobody reads, and a
 * needlessly wide window onto other people's correspondence. Deciding per
 * endpoint how much of a record to expose is a design step, not an oversight.
 */
public class OutboxMessageResponse {

    private Long id;
    private String recipient;
    private String recipientName;
    private String subject;
    private String body;
    private String templateKey;
    private String dedupeKey;
    private String status;
    private int attempts;
    private Instant nextAttemptAt;
    private Instant sentAt;
    private String lastError;
    private String correlationId;
    private Instant createdAt;

    /** JSON-B needs a no-argument constructor. */
    public OutboxMessageResponse() {
    }

    /** The summary shape used by the list endpoint: everything but the body. */
    public static OutboxMessageResponse summary(OutboxMessage row) {
        OutboxMessageResponse dto = new OutboxMessageResponse();
        dto.id = row.getId();
        dto.recipient = row.getRecipient().getValue();
        dto.recipientName = row.getRecipientName();
        dto.subject = row.getSubject();
        dto.templateKey = row.getTemplateKey();
        dto.dedupeKey = row.getDedupeKey();
        dto.status = row.getStatus().name();
        dto.attempts = row.getAttempts();
        dto.nextAttemptAt = row.getNextAttemptAt();
        dto.sentAt = row.getSentAt();
        dto.lastError = row.getLastError();
        dto.correlationId = row.getCorrelationId();
        dto.createdAt = row.getCreatedAt();
        return dto;
    }

    /** The full shape used by the single-message endpoint. */
    public static OutboxMessageResponse full(OutboxMessage row) {
        OutboxMessageResponse dto = summary(row);
        dto.body = row.getBody();
        return dto;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public void setTemplateKey(String templateKey) {
        this.templateKey = templateKey;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
