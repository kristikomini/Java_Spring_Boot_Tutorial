package it.unicam.cs.enrollment.mail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * <h2>03:40, not 03:30</h2>
 * {@code FieldbookMaintenanceJob} already sweeps at 03:20 and 03:30. Stacking
 * every nightly job on the same minute creates a load spike and, worse, makes
 * any lock contention between them look like a mystery. Spreading them by ten
 * minutes costs nothing and is the kind of thing a team learns to do once.
 *
 * <h2>No {@code @Transactional} here, on purpose</h2>
 * {@code MailService.purgeOldMessages} opens its own. The delete is one bulk
 * statement and belongs in a transaction of its own, not in one that also spans
 * this method's logging - and a transaction opened here would be the OUTER one,
 * so the service's annotation would join it rather than replace it. That is
 * Spring's default {@code REQUIRED} propagation, and misreading it is how
 * people end up with one enormous transaction they did not intend.
 */
@Component
public class MailRetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(MailRetentionJob.class);

    private final MailService mail;

    public MailRetentionJob(MailService mail) {
        this.mail = mail;
    }

    @Scheduled(cron = "0 40 3 * * *")
    public void purge() {
        int deleted = mail.purgeOldMessages();
        LOG.info("Mail retention purge finished: {} delivered message(s) removed", deleted);
    }
}
