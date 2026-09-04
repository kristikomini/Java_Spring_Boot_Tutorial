package it.unicam.cs.enrollment.mail.transport;

import it.unicam.cs.enrollment.mail.domain.MailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The development transport: prints the email to the server log and reports
 * success.
 *
 * <h2>Why a fake that always succeeds is worth shipping</h2>
 * Because the interesting parts of a mailing system are not the SMTP
 * conversation. They are: does the row get written in the same transaction as
 * the enrollment; does the dispatcher find it; does the template render the
 * student's name; does a failure back off. All of that can be exercised - and
 * watched, in {@code docker compose logs -f wildfly} - with no mail server
 * anywhere near the machine.
 *
 * <p>It is also the honest default. A learning stack that tried real SMTP on
 * first run would greet the reader with a queue full of dead messages and a
 * connection-refused stack trace, which teaches nothing about mail and quite a
 * lot about giving up.
 *
 * <h2>The obvious objection</h2>
 * A transport that never fails hides every bug that only appears when delivery
 * is hard. That is exactly right, and it is why the compose stack runs Mailpit:
 * a real SMTP server, listening on a real port, with a web inbox at
 * <a href="http://localhost:8025">localhost:8025</a>. Log-only is the fallback
 * for when it is not running, not the recommended way to work.
 *
 * <h2>A word about what is being logged</h2>
 * This prints a real name and a real address into a log file. That is
 * acceptable here because the log is a developer's own console and the data is
 * seeded fiction. In production it would be a privacy incident of the ordinary,
 * boring kind: logs get shipped to a search cluster, retained for a year, and
 * read by people who never needed the student's address. Hence the WARN on
 * startup if this transport is ever selected outside development.
 */
public class LoggingMailTransport implements MailTransport {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingMailTransport.class);

    private final String reason;

    /**
     * @param reason why this transport was chosen - carried into
     *               {@link #describe()} so the mailbox API can tell the
     *               difference between "configured for log" and "SMTP was
     *               missing, so we fell back"
     */
    public LoggingMailTransport(String reason) {
        this.reason = reason;
    }

    @Override
    public void send(MailMessage message) {
        // One multi-line statement rather than several log calls: a single call
        // is written to the appender atomically, so the message cannot be
        // interleaved with another thread's output and become unreadable.
        LOG.info("\n"
                        + "+----------------------------------------------------------------+\n"
                        + "| MAIL (not actually sent - {} )\n"
                        + "+----------------------------------------------------------------+\n"
                        + "| To:      {}\n"
                        + "| Subject: {}\n"
                        + "+----------------------------------------------------------------+\n"
                        + "{}\n"
                        + "+----------------------------------------------------------------+",
                reason,
                message.formattedRecipient(),
                message.getSubject(),
                message.getBody());
    }

    @Override
    public String describe() {
        return "log only (" + reason + ")";
    }
}
