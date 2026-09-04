package it.unicam.cs.enrollment.mail.service;

import it.unicam.cs.enrollment.mail.MailConfig;
import it.unicam.cs.enrollment.mail.domain.MailMessage;
import it.unicam.cs.enrollment.mail.transport.MailDeliveryException;
import it.unicam.cs.enrollment.mail.transport.MailTransport;
import jakarta.ejb.ConcurrencyManagement;
import jakarta.ejb.ConcurrencyManagementType;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <h2>{@code @Singleton} plus container concurrency</h2>
 * The EJB singleton's default write lock means only one thread is inside this
 * bean at a time, so a sweep that overruns its interval cannot start a second
 * copy of itself and send everything twice. Getting that for free is why a
 * scheduled component is still an EJB in a codebase that is otherwise all CDI.
 *
 * <h2>{@code NOT_SUPPORTED} on the class</h2>
 * Suspends any transaction for the duration of these methods, so the SMTP
 * conversation provably happens with no transaction open. The database work is
 * done by {@link OutboxProcessor}, whose methods each start their own. See that
 * class for why it must be a different bean.
 */
@Singleton
@ConcurrencyManagement(ConcurrencyManagementType.CONTAINER)
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class MailDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(MailDispatcher.class);

    // FIELD injection here, where the service layer uses CONSTRUCTOR injection.
    // Not a slip: an EJB session bean is required to have a no-argument
    // constructor, so the constructor cannot carry the dependencies. The fields
    // are package-private rather than private so the test in this package can
    // set them without reflection - a small, deliberate widening of visibility
    // for a real reason, the same trade AbstractJpaRepository makes.
    @Inject
    OutboxProcessor processor;

    @Inject
    MailTransport transport;

    @Inject
    MailConfig config;

    @Inject
    Clock clock;

    /**
     * The main loop, every thirty seconds.
     *
     * <p>{@code persistent = false} for the same reason as the enrollment
     * sweep: a persistent timer lives in the server's timer database and is
     * recreated by every node in a cluster, which is how a nightly job runs
     * four times. See {@code EnrollmentMaintenanceJob} for the longer version.
     */
    @Schedule(second = "*/30", minute = "*", hour = "*", persistent = false,
            info = "Mail outbox dispatch")
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
    @Schedule(minute = "*/5", hour = "*", persistent = false,
            info = "Mail outbox stuck-message recovery")
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
