package it.unicam.cs.enrollment.mail.service;

import it.unicam.cs.enrollment.api.filter.CorrelationIdFilter;
import it.unicam.cs.enrollment.common.Page;
import it.unicam.cs.enrollment.common.PageRequest;
import it.unicam.cs.enrollment.exception.ResourceNotFoundException;
import it.unicam.cs.enrollment.mail.MailConfig;
import it.unicam.cs.enrollment.mail.domain.MailMessage;
import it.unicam.cs.enrollment.mail.domain.MailStatus;
import it.unicam.cs.enrollment.mail.domain.OutboxMessage;
import it.unicam.cs.enrollment.mail.repository.MailOutboxRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The way application code asks for an email: {@code mailService.enqueue(...)}.
 *
 * <h2>What this class does not do</h2>
 * It does not send anything. Not one line of it opens a socket. Everything here
 * is a database write, which is what makes the promise in
 * {@link OutboxMessage}'s javadoc real: queuing an email joins the CALLER's
 * transaction, so it commits with the enrollment or disappears with it.
 *
 * <p>{@code @Transactional} without arguments means {@code REQUIRED}: join the
 * caller's transaction if there is one, start one otherwise. That is the right
 * default here and the reason it is stated rather than left off - the class
 * would behave identically today, and the next person to call it from a place
 * with no transaction would get a silent surprise.
 *
 * <h2>Reading this class as an example</h2>
 * The interesting part is not any single method - they are all short - but the
 * fact that the queueing side and the sending side of a mail system are
 * different components with different failure modes, different transaction
 * boundaries, and different reasons to be woken up. Splitting them is most of
 * what makes the thing reliable.
 */
@ApplicationScoped
public class MailService {

    private MailOutboxRepository outbox;
    private MailTemplates templates;
    private MailConfig config;
    private Clock clock;
    private Logger log;

    /** Required by CDI for proxying. Never call it yourself. */
    protected MailService() {
        // required by CDI
    }

    /**
     * Constructor injection, matching the rest of the service layer: the
     * dependencies are visible in one signature, they can be made final, and a
     * unit test builds one with five mocks and no container.
     */
    @Inject
    public MailService(MailOutboxRepository outbox,
                       MailTemplates templates,
                       MailConfig config,
                       Clock clock,
                       Logger log) {
        this.outbox = outbox;
        this.templates = templates;
        this.config = config;
        this.clock = clock;
        this.log = log;
    }

    /**
     * Queue one message for delivery.
     *
     * <h3>The dedupe check</h3>
     * When the message carries a dedupe key that is already in the table, this
     * returns the EXISTING row and writes nothing. That makes {@code enqueue}
     * idempotent, which matters more than it first appears: a service method
     * that queues mail and then fails is going to be retried, by a user clicking
     * again if by nothing else, and the second run must not produce a second
     * email.
     *
     * <h3>Why it returns the row</h3>
     * So the caller can log an id, and so a test can assert on state without
     * reaching into the repository. Note that it is returned MANAGED and inside
     * the caller's transaction - which is fine here, and would not be if this
     * were handed to an asynchronous observer. See {@link MailMessage} for the
     * value-object alternative that has no such rule attached.
     */
    @Transactional
    public OutboxMessage enqueue(MailMessage message) {
        Optional<String> dedupeKey = message.getDedupeKey();
        if (dedupeKey.isPresent()) {
            Optional<OutboxMessage> existing = outbox.findByDedupeKey(dedupeKey.get());
            if (existing.isPresent()) {
                log.debug("Mail with key {} is already queued as #{} - not queuing it again",
                        dedupeKey.get(), existing.get().getId());
                return existing.get();
            }
        }

        MailMessage prefixed = applySubjectPrefix(message);
        OutboxMessage row = OutboxMessage.queue(prefixed, clock.instant());
        row.setCorrelationId(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY));

        OutboxMessage saved = outbox.save(row);

        log.info("Queued mail '{}' to {} (template={}, id={})",
                saved.getSubject(), saved.getRecipient().getValue(),
                saved.getTemplateKey(), saved.getId());

        return saved;
    }

    /**
     * Render a template and queue the result - the call almost every caller
     * actually wants.
     *
     * <p>Two steps rather than one would have every listener repeating the same
     * three lines, and repetition is where the version that forgets the dedupe
     * key comes from.
     */
    @Transactional
    public OutboxMessage enqueueTemplate(String templateKey,
                                         String recipient,
                                         String recipientName,
                                         Map<String, String> model,
                                         String dedupeKey) {
        MailTemplates.RenderedMail rendered = templates.render(templateKey, model);
        return enqueue(MailMessage.to(recipient)
                .named(recipientName)
                .subject(rendered.subject())
                .body(rendered.body())
                .fromTemplate(templateKey)
                .dedupeKey(dedupeKey)
                .build());
    }

    /**
     * The subject prefix is applied HERE, before the row is written, so that the
     * outbox shows exactly the subject that will be delivered. Applying it in
     * the transport instead would make the stored row a near-miss of the real
     * message, and "near-miss" is the worst property an audit record can have.
     */
    private MailMessage applySubjectPrefix(MailMessage message) {
        String prefix = config.getSubjectPrefix();
        if (prefix == null || prefix.trim().isEmpty()) {
            return message;
        }
        // The separating space is added here rather than expected in the
        // configured value. Environment variables lose leading and trailing
        // whitespace on the way through a shell, a Dockerfile and a Kubernetes
        // manifest, so a prefix that depends on a trailing space is a prefix
        // that works on someone's laptop and produces "[DEV]You are enrolled"
        // in the cluster. Never require whitespace to survive a config pipeline.
        return MailMessage.to(message.getRecipient())
                .named(message.getRecipientName().orElse(null))
                .subject(prefix.trim() + " " + message.getSubject())
                .body(message.getBody())
                .fromTemplate(message.getTemplateKey().orElse(null))
                .dedupeKey(message.getDedupeKey().orElse(null))
                .build();
    }

    // ------------------------------------------------------------------
    // Operator actions, exposed through the mailbox API
    // ------------------------------------------------------------------

    /** Put a dead or cancelled message back in the queue, with a fresh budget. */
    @Transactional
    public OutboxMessage requeue(Long id) {
        OutboxMessage row = require(id);
        row.requeue(clock.instant());
        log.info("Mail #{} to {} was requeued by hand", id, row.getRecipient().getValue());
        return row;
    }

    /** Stop a message that has not gone out yet. */
    @Transactional
    public OutboxMessage cancel(Long id) {
        OutboxMessage row = require(id);
        row.cancel();
        log.info("Mail #{} to {} was cancelled by hand", id, row.getRecipient().getValue());
        return row;
    }

    /**
     * Read-only, and still transactional.
     *
     * <p>A read outside a transaction gets its own short one per query, so two
     * queries in the same method - and {@code findByStatus} issues two, one for
     * the page and one for the count - can see two different states of the
     * database. Wrapping the pair means the total matches the rows.
     */
    @Transactional
    public Page<OutboxMessage> list(MailStatus status, PageRequest pageRequest) {
        return outbox.findByStatus(status, pageRequest);
    }

    @Transactional
    public OutboxMessage findById(Long id) {
        return require(id);
    }

    @Transactional
    public Map<MailStatus, Long> countByStatus() {
        return outbox.countByStatus();
    }

    /**
     * Deletes delivered messages older than the retention window.
     *
     * <p>Called by the nightly job. A queue table without a purge grows without
     * bound, and the first symptom is not disk space - it is the dispatcher's
     * index scan getting slower every week until someone notices that mail is
     * arriving late.
     */
    @Transactional
    public int purgeOldMessages() {
        Instant cutoff = clock.instant().minusSeconds(config.getRetentionDays() * 86_400L);
        int deleted = outbox.purgeSentBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} delivered message(s) sent before {}", deleted, cutoff);
        }
        return deleted;
    }

    private OutboxMessage require(Long id) {
        return outbox.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Mail message", id));
    }
}
