package it.unicam.cs.enrollment.mail.service;

import jakarta.ejb.ConcurrencyManagement;
import jakarta.ejb.ConcurrencyManagementType;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes delivered mail once it is older than the retention window.
 *
 * <h2>Why a queue table needs a gardener</h2>
 * Every row this application ever emails stays in {@code mail_outbox} forever
 * unless something removes it. Nothing dramatic happens at first - and then the
 * dispatcher's "PENDING and due" query, which touches an index over a table
 * that is now 99.99% delivered mail, gets slower every month. The symptom is
 * mail arriving late, and the cause is three years of successful sends nobody
 * needed.
 *
 * <p>Retention is a decision, not a technicality: how long is this record
 * useful, and to whom? Thirty days is long enough to answer "did we send it?"
 * for any support request that is still open, and short enough that the table
 * stays small. Somebody in a real institution would have an opinion about the
 * number, backed by a policy - which is why it is configuration
 * ({@code ENROLLMENT_MAIL_RETENTION_DAYS}) rather than a constant.
 *
 * <p>Note what is NOT purged: DEAD messages, which are the record of mail
 * somebody was promised and never received. Deleting the evidence of failure on
 * a schedule is how a failure stops being fixed.
 *
 * <h2>03:30, not 03:00</h2>
 * {@code EnrollmentMaintenanceJob} already sweeps at 03:00. Stacking every
 * nightly job on the same minute creates a load spike and, worse, makes any
 * lock contention between them look like a mystery. Spreading them by half an
 * hour costs nothing and is the kind of thing a team learns to do once.
 */
@Singleton
@ConcurrencyManagement(ConcurrencyManagementType.CONTAINER)
public class MailRetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(MailRetentionJob.class);

    @Inject
    MailService mail;

    @Schedule(hour = "3", minute = "30", second = "0", persistent = false,
            info = "Mail outbox retention purge")
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void purge() {
        // NOT_SUPPORTED, then MailService starts its own transaction: the delete
        // is one bulk statement and belongs in a transaction of its own, not in
        // one that also spans the timer callback's own bookkeeping.
        int deleted = mail.purgeOldMessages();
        LOG.info("Mail retention purge finished: {} delivered message(s) removed", deleted);
    }
}
