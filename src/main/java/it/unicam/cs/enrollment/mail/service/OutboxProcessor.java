package it.unicam.cs.enrollment.mail.service;

import it.unicam.cs.enrollment.mail.MailConfig;
import it.unicam.cs.enrollment.mail.domain.MailMessage;
import it.unicam.cs.enrollment.mail.domain.OutboxMessage;
import it.unicam.cs.enrollment.mail.repository.MailOutboxRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The transactional half of the dispatcher: everything that touches the
 * database, and nothing that touches the network.
 *
 * <h2>Why this is a separate class from {@code MailDispatcher}</h2>
 * This is the single most useful thing in the mail package to understand.
 *
 * <p>The dispatcher needs each message handled in its OWN short transaction,
 * with the SMTP conversation happening outside any transaction at all. The
 * obvious implementation puts both in one class and annotates the inner method
 * {@code @Transactional(REQUIRES_NEW)}:
 *
 * <pre>
 *   public void dispatchAll() {
 *       for (Long id : ids) {
 *           processOne(id);            // &lt;-- a plain Java call on `this`
 *       }
 *   }
 *
 *   &#64;Transactional(REQUIRES_NEW)
 *   void processOne(Long id) { ... }   // &lt;-- and the annotation does NOTHING
 *   </pre>
 *
 * <p>It does nothing because interceptors - {@code @Transactional},
 * {@code @Loggable}, security, everything in CDI and EJB - are implemented by a
 * PROXY that wraps the bean. Callers hold the proxy; the bean's own {@code this}
 * is the naked instance behind it. A self-call goes straight to the method and
 * never passes the proxy, so no transaction is started, no log line is written,
 * no security check runs. The code looks right, compiles, deploys, and quietly
 * behaves as if the annotation were a comment.
 *
 * <p>It is one of the most common Jakarta EE and Spring bugs there is, and the
 * cure is always the same: move the annotated method to a DIFFERENT bean and
 * inject it, so the call goes through a proxy. That is exactly why this class
 * exists, and splitting it also happens to leave two classes each of which does
 * one thing.
 *
 * <h2>Transaction boundaries, spelled out</h2>
 * <pre>
 *   [tx 1] find the due ids                       - short read
 *   [tx 2] claim one: PENDING -&gt; SENDING, commit   - short write
 *          ... talk to the SMTP server ...        - NO transaction open
 *   [tx 3] record the outcome: SENT / PENDING / DEAD
 * </pre>
 * The middle step is the point. A transaction holds a database connection from
 * the pool and, on many schemas, row locks; keeping one open across a network
 * call to a third party means one slow mail server can exhaust the connection
 * pool and take the whole application down with it. Never hold a transaction
 * across an external call.
 */
@ApplicationScoped
public class OutboxProcessor {

    private MailOutboxRepository outbox;
    private MailConfig config;
    private Logger log;

    /** Required by CDI for proxying. Never call it yourself. */
    protected OutboxProcessor() {
        // required by CDI
    }

    @Inject
    public OutboxProcessor(MailOutboxRepository outbox, MailConfig config, Logger log) {
        this.outbox = outbox;
        this.config = config;
        this.log = log;
    }

    /** Ids of messages whose time has come. Its own transaction, deliberately short. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Long> findDue(Instant now, int limit) {
        return outbox.findDueIds(now, limit);
    }

    /** Ids of messages abandoned mid-send by a dispatcher that is no longer running. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Long> findStuck(Instant now, int limit) {
        return outbox.findStuckIds(now.minus(config.getStuckAfter()), limit);
    }

    /**
     * Take ownership of one message and return the snapshot to send.
     *
     * <h3>Why it can return empty, and why that is not an error</h3>
     * Between the {@code findDue} query and this call, the row may have been
     * cancelled by an operator, or claimed by another dispatcher. Re-checking
     * the status INSIDE the claiming transaction is what makes the check
     * meaningful: the row is read and written in one transaction, so two
     * claimants cannot both see PENDING and both proceed - the second one's
     * commit fails on the version column, or it simply reads SENDING and gives
     * up here.
     *
     * <p>Returning a {@link MailMessage} rather than the entity is the other
     * half of the design. The entity becomes detached the moment this
     * transaction commits, and the caller is about to spend a network round trip
     * before touching it again. Handing back a value makes that safe by
     * construction instead of by remembering.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<MailMessage> claim(Long id, Instant now) {
        Optional<OutboxMessage> found = outbox.findById(id);
        if (!found.isPresent()) {
            return Optional.empty();
        }
        OutboxMessage row = found.get();
        if (!row.isDue(now)) {
            log.debug("Mail #{} is no longer claimable (status={})", id, row.getStatus());
            return Optional.empty();
        }
        row.markSending(now);
        // flush() so the UPDATE - and any optimistic-lock failure it causes -
        // happens here, while we can still report it, rather than at commit
        // from inside the interceptor where the stack trace says nothing useful.
        outbox.flush();
        return Optional.of(row.toMailMessage());
    }

    /** The transport accepted it. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void recordSuccess(Long id, Instant now) {
        outbox.findById(id).ifPresent(row -> row.markSent(now));
    }

    /** The transport refused it; the entity decides between retry and dead-letter. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void recordFailure(Long id, String error, boolean permanent, Instant now) {
        outbox.findById(id).ifPresent(row -> {
            row.markFailed(error, permanent, config.getRetryPolicy(), now);
            log.warn("Mail #{} to {} failed on attempt {} ({}): {}",
                    id, row.getRecipient().getValue(), row.getAttempts(),
                    row.getStatus(), error);
        });
    }

    /** Hand an abandoned message back to the queue. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean release(Long id, Instant now) {
        Optional<OutboxMessage> found = outbox.findById(id);
        if (!found.isPresent() || !found.get().isStuck(now, config.getStuckAfter())) {
            return false;
        }
        found.get().releaseStuckClaim(now);
        log.warn("Mail #{} was left in SENDING and has been re-queued", id);
        return true;
    }
}
