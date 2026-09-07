package it.unicam.cs.enrollment.mail.service;

import it.unicam.cs.enrollment.mail.MailConfig;
import it.unicam.cs.enrollment.mail.domain.MailMessage;
import it.unicam.cs.enrollment.mail.transport.MailDeliveryException;
import it.unicam.cs.enrollment.mail.transport.MailTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The process that actually sends the mail: every thirty seconds, take whatever
 * is due out of the outbox and hand it to the transport.
 *
 * <h2>Polling, and the honest case for it</h2>
 * A timer that queries a table is not elegant. A message broker would wake a
 * consumer the instant a row appeared, with no wasted queries and no thirty
 * second worst-case delay - and that is the right answer at scale.
 *
 * <p>Polling wins here on operational cost. There is no broker to run, no
 * second thing that can be down, no delivery semantics to reason about beyond
 * the ones already in the table, and the whole mechanism is visible in one
 * class that anyone can read. For a queue measured in dozens of messages a day,
 * with a tolerance for a half-minute delay, a poll is the correct engineering
 * choice rather than a compromise. Knowing WHEN it stops being correct - when
 * the query costs more than the work, or the latency starts to matter - is the
 * part worth carrying forward.
 *
 * <h2>Why one sweep cannot overtake another</h2>
 * Spring's default {@code TaskScheduler} is a pool of ONE thread and runs
 * {@code @Scheduled} methods sequentially, so a sweep that overruns its
 * thirty-second interval delays the next firing rather than starting a second
 * copy of itself. Worth stating because it is easy to lose: raise
 * {@code spring.task.scheduling.pool.size} and this guarantee goes away, and
 * two dispatchers racing would try to send the same message twice. The claim
 * step in {@link OutboxProcessor} is what makes that safe even so - defence in
 * depth rather than a reason to be careless with the pool size.
 *
 * <h2>No {@code @Transactional} anywhere in this class</h2>
 * That is the design, not an omission. Every method here runs with NO
 * transaction open, so the SMTP conversation provably holds no database
 * connection and no row locks. All the database work is delegated to
 * {@link OutboxProcessor}, whose methods each start their own short
 * transaction. See that class for why it must be a different bean.
 */
@Component
public class MailDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(MailDispatcher.class);

    private final OutboxProcessor processor;
    private final MailTransport transport;
    private final MailConfig config;
    private final Clock clock;

    public MailDispatcher(OutboxProcessor processor, MailTransport transport,
                          MailConfig config, Clock clock) {
        this.processor = processor;
        this.transport = transport;
        this.config = config;
        this.clock = clock;
    }

    /**
     * The main loop, every thirty seconds.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}: the delay is
     * measured from the END of the previous run, so a slow pass pushes the next
     * one back instead of queueing firings behind it. On a job that talks to a
     * remote SMTP server - which can be slow for minutes at a time -
     * {@code fixedRate} would build a backlog of scheduled executions that all
     * run the moment the server recovers.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void dispatchDue() {
        if (!config.isEnabled()) {
            LOG.debug("Mail delivery is disabled - {} message(s) will stay queued",
                    processor.findDue(clock.instant(), config.getBatchSize()).size());
            return;
        }
        dispatchOnce();
    }

    /**
     * One pass over the due messages. Returns how many were accepted by the
     * transport.
     *
     * <p>Separate from the scheduled method, and public, so that the mailbox API
     * can offer a "flush now" button and a test can drive a pass without waiting
     * for a timer. A scheduled job you cannot trigger by hand is a scheduled job
     * you cannot debug.
     */
    public int dispatchOnce() {
        Instant now = clock.instant();
        List<Long> due = processor.findDue(now, config.getBatchSize());
        if (due.isEmpty()) {
            return 0;
        }

        LOG.debug("Dispatching {} due message(s) via {}", due.size(), transport.describe());

        int sent = 0;
        for (Long id : due) {
            if (dispatchOne(id)) {
                sent++;
            }
        }

        LOG.info("Mail dispatch finished: {} sent, {} failed, transport={}",
                sent, due.size() - sent, transport.describe());
        return sent;
    }

    /**
     * Claim, send, record. The three steps are three transactions, and the send
     * is in none of them.
     *
     * <h3>The unavoidable window</h3>
     * If this JVM dies between {@code transport.send} returning and
     * {@code recordSuccess} committing, the message has been delivered and the
     * row still says SENDING. The recovery sweep will later re-queue it and the
     * student gets the email twice.
     *
     * <p>That window cannot be closed - it is the classic two-generals problem,
     * and no amount of cleverness makes "the far end accepted it" and "we wrote
     * that down" a single atomic act across two systems. What CAN be chosen is
     * which way it fails: this design duplicates rather than loses. For a
     * confirmation email that is plainly the right call. For "charge the credit
     * card" it is plainly the wrong one, and that is why payment APIs make you
     * send an idempotency key - they move the deduplication to the side that can
     * actually do it.
     */
    private boolean dispatchOne(Long id) {
        Instant now = clock.instant();

        Optional<MailMessage> claimed = processor.claim(id, now);
        if (!claimed.isPresent()) {
            // Someone else got there first, or an operator cancelled it. Not an
            // error, and specifically not worth a WARN: a log line nobody needs
            // to act on trains people to ignore the ones they do.
            return false;
        }

        MailMessage message = claimed.get();
        try {
            transport.send(message);
            processor.recordSuccess(id, clock.instant());
            return true;

        } catch (MailDeliveryException e) {
            processor.recordFailure(id, e.getMessage(), e.isPermanent(), clock.instant());
            return false;

        } catch (RuntimeException e) {
            // A transport that throws something undeclared is a bug in the
            // transport, not a delivery outcome. It is caught anyway, because
            // the alternative is one broken message aborting the whole batch and
            // blocking every message behind it - a queue must never be stoppable
            // by a single bad entry.
            LOG.error("Unexpected error while sending mail #{}", id, e);
            processor.recordFailure(id, "Unexpected " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), false, clock.instant());
            return false;
        }
    }

    /**
     * Rescues messages left mid-flight by a dispatcher that stopped existing:
     * a redeploy in the middle of a batch, a killed container, an OOM.
     *
     * <p>Every claim-based queue needs this sweep, and forgetting it is a
     * standard way to build a system that works perfectly until the first
     * unplanned restart and then silently drops whatever was in flight. Running
     * it every five minutes, against a ten-minute claim age, keeps it well clear
     * of a dispatcher that is merely slow.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void recoverStuckMessages() {
        Instant now = clock.instant();
        List<Long> stuck = processor.findStuck(now, config.getBatchSize());
        if (stuck.isEmpty()) {
            return;
        }

        int released = 0;
        for (Long id : stuck) {
            if (processor.release(id, now)) {
                released++;
            }
        }
        LOG.warn("Recovered {} message(s) abandoned in SENDING - a dispatcher stopped mid-send",
                released);
    }
}
