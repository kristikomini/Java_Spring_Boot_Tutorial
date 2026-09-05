package it.unicam.cs.enrollment.fieldbook.service;

import it.unicam.cs.enrollment.fieldbook.repository.AuthSessionRepository;
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

/**
 * Deletes expired sessions once a night.
 *
 * <h2>Why a sweeper exists when expiry is already checked on read</h2>
 * {@code AccountService.resolve} refuses an expired session and deletes the row
 * it found, so security does not depend on this job at all. What the job
 * removes is the rows nobody ever looks at again - the session belonging to a
 * browser that was closed and never reopened. Without it the table grows
 * forever with rows that can never be used, and one day somebody wonders why a
 * table of live sessions has four million of them.
 *
 * <p>The distinction is worth naming: correctness on the read path, hygiene on
 * the schedule. A design that relies on the sweeper for correctness has a
 * security hole for as long as the sweeper is down.
 *
 * <h2>{@code @Singleton} and container concurrency</h2>
 * One instance for the whole application, and by default the container holds a
 * write lock around every method - so two timer firings can never overlap. That
 * is what you want for a job like this, and it is also why a slow scheduled
 * method blocks the next firing rather than running twice.
 *
 * <p>{@code persistent = false} keeps the timer in memory. A persistent timer
 * is stored in the database and survives a restart, which sounds better until
 * you notice every node in a cluster then fires the same job. Real answers are
 * a distributed lock or a scheduler that owns the cluster. See the fieldbook
 * chapter on scheduled work for the longer version.
 */
@Singleton
@ConcurrencyManagement(ConcurrencyManagementType.CONTAINER)
public class FieldbookMaintenanceJob {

    private static final Logger LOG = LoggerFactory.getLogger(FieldbookMaintenanceJob.class);

    @Inject
    AuthSessionRepository sessions;

    @Inject
    AccountService accounts;

    @Inject
    Clock clock;

    @Schedule(hour = "3", minute = "20", second = "0", persistent = false,
            info = "Delete fieldbook sessions that have expired")
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void sweepExpiredSessions() {
        int removed = sessions.deleteExpired(clock.instant());
        if (removed > 0) {
            LOG.info("Swept {} expired fieldbook sessions", removed);
        }
    }

    /**
     * Drop password reset rows once they are past the audit window.
     *
     * <p>A separate schedule rather than two statements in the method above,
     * and ten minutes later rather than at the same instant. Two reasons, and
     * the second is the one worth remembering: each sweep is its own
     * transaction, so a failure in one does not roll back the other; and two
     * bulk deletes firing simultaneously against tables that share a parent is
     * how a nightly job starts deadlocking against itself at three in the
     * morning, which is the worst time to be reading a stack trace.
     */
    @Schedule(hour = "3", minute = "30", second = "0", persistent = false,
            info = "Delete spent and expired fieldbook password reset tokens")
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void sweepSpentPasswordResets() {
        int removed = accounts.sweepExpiredResets();
        if (removed > 0) {
            LOG.info("Swept {} spent or expired password reset tokens", removed);
        }
    }
}
