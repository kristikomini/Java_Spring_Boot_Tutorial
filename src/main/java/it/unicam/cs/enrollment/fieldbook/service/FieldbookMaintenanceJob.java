package it.unicam.cs.enrollment.fieldbook.service;

import it.unicam.cs.enrollment.fieldbook.repository.AuthSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 * <h2>{@code @Scheduled}, and the one line that makes it run</h2>
 * The annotation alone does nothing. {@code @EnableScheduling} on
 * {@code EnrollmentApplication} is what creates the {@code TaskScheduler} that
 * finds these methods; without it they compile, deploy and never fire - which
 * is a genuinely annoying afternoon, because there is no error to search for.
 *
 * <p>The cron expression is Spring's SIX-field variant: second, minute, hour,
 * day-of-month, month, day-of-week. Unix cron has five fields and starts at
 * minutes, so a five-field expression pasted from a crontab is silently
 * shifted by one position and runs at the wrong time. That is the single most
 * common mistake with this annotation.
 *
 * <h2>One scheduler thread, and what that implies</h2>
 * Spring's default {@code TaskScheduler} has a pool size of ONE, so scheduled
 * methods across the whole application are serialised: a slow job delays the
 * next one rather than running beside it. That happens to be what these sweeps
 * want, and it is worth knowing before a long job starves a frequent one.
 *
 * <p>What Spring does NOT give you is cluster safety. Run two instances and
 * both fire this job at 03:20. For a delete-by-predicate sweep that is merely
 * wasteful, but the general answer is a distributed lock (ShedLock is the usual
 * library) or a scheduler that owns the cluster (Quartz with a JDBC store).
 * See the fieldbook chapter on scheduled work for the longer version.
 */
@Component
public class FieldbookMaintenanceJob {

    private static final Logger LOG = LoggerFactory.getLogger(FieldbookMaintenanceJob.class);

    private final AuthSessionRepository sessions;
    private final AccountService accounts;
    private final Clock clock;

    public FieldbookMaintenanceJob(AuthSessionRepository sessions, AccountService accounts, Clock clock) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** 03:20 every night. */
    @Scheduled(cron = "0 20 3 * * *")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sweepSpentPasswordResets() {
        int removed = accounts.sweepExpiredResets();
        if (removed > 0) {
            LOG.info("Swept {} spent or expired password reset tokens", removed);
        }
    }
}
