package it.unicam.cs.enrollment.mail.transport;

import it.unicam.cs.enrollment.mail.MailConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.mail.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * Chooses which {@link MailTransport} the application runs with, and creates it.
 *
 * <h2>Why a producer method</h2>
 * CDI injects by TYPE. Two classes implement {@code MailTransport}, so
 * {@code @Inject MailTransport} would be ambiguous and the deployment would
 * fail - correctly, because the container genuinely cannot guess which one is
 * meant. The three standard answers are {@code @Alternative} (chosen in
 * beans.xml), a {@code @Qualifier} (chosen at every injection point), and a
 * {@code @Produces} method (chosen once, in code, at runtime).
 *
 * <p>A producer is right here because the decision depends on something only
 * discoverable while starting up: whether a mail session actually exists in
 * JNDI. Neither of the other two mechanisms can ask a question.
 *
 * <p>Note also that the two implementations carry no scope annotation. With
 * {@code bean-discovery-mode="annotated"} in beans.xml that makes them
 * ordinary classes rather than beans - so they cannot be injected by accident,
 * and this method is the only way to get one. Deciding what is NOT a bean is
 * part of designing with CDI.
 *
 * <h2>{@code @ApplicationScoped} on the producer method</h2>
 * Without it the method would run at every injection point ({@code @Dependent}
 * is the default), doing a JNDI lookup each time. With it, the transport is
 * created once and shared - which is why the interface requires thread safety.
 */
@ApplicationScoped
public class MailTransportProducer {

    private static final Logger LOG = LoggerFactory.getLogger(MailTransportProducer.class);

    @Inject
    MailConfig config;

    @Produces
    @ApplicationScoped
    public MailTransport mailTransport() {
        switch (config.getTransportMode()) {
            case LOG:
                LOG.info("Mail transport: log only (enrollment.mail.transport=log)");
                return new LoggingMailTransport("configured with transport=log");

            case SMTP:
                // The mode says SMTP, so a missing session is a configuration
                // error, not something to paper over. Failing at STARTUP means
                // whoever deployed it finds out immediately, instead of a
                // student finding out three days later that no mail ever went.
                return new SmtpMailTransport(requireSession(), config, config.getSessionJndiName());

            case AUTO:
            default:
                return autoSelect();
        }
    }

    /**
     * Use SMTP if a session is there; otherwise say so, loudly, and carry on.
     *
     * <h3>The judgement call</h3>
     * Degrading to a fake transport is normally a bad habit: the system reports
     * success while doing nothing, which is how a "working" deployment silently
     * sends no mail for a week. It is the right default HERE because the primary
     * audience is a reader running this on a laptop, and a deployment that
     * refuses to start because there is no SMTP server on their machine teaches
     * them nothing about Jakarta EE.
     *
     * <p>The mitigations are what make it defensible: the fallback is logged at
     * WARN with the reason, the mailbox API reports which transport is live, and
     * {@code transport=smtp} turns the degradation off for any environment that
     * cares. A fallback nobody can see is the dangerous kind; this one announces
     * itself in three places.
     */
    private MailTransport autoSelect() {
        String jndiName = config.getSessionJndiName();
        try {
            Session session = lookupSession(jndiName);
            LOG.info("Mail transport: SMTP via {}", jndiName);
            return new SmtpMailTransport(session, config, jndiName);
        } catch (NamingException e) {
            LOG.warn("No mail session at {} - falling back to the log-only transport. "
                            + "No email will actually be sent. Start the Mailpit container "
                            + "(docker compose up -d mailpit) or set enrollment.mail.transport=smtp "
                            + "to make this a startup failure instead.",
                    jndiName);
            return new LoggingMailTransport("no mail session at " + jndiName);
        }
    }

    private Session requireSession() {
        String jndiName = config.getSessionJndiName();
        try {
            Session session = lookupSession(jndiName);
            LOG.info("Mail transport: SMTP via {}", jndiName);
            return session;
        } catch (NamingException e) {
            throw new IllegalStateException(
                    "enrollment.mail.transport=smtp but no jakarta.mail.Session is bound at "
                            + jndiName + ". Configure one in the application server, or use "
                            + "transport=auto/log.", e);
        }
    }

    /**
     * A programmatic JNDI lookup rather than {@code @Resource} injection.
     *
     * <p>{@code @Resource(lookup = "java:jboss/mail/Enrollment")} is the
     * idiomatic form and would be shorter. It is also unconditional: the
     * container resolves it while deploying, and a server without that session
     * fails the whole deployment - including the parts of this application that
     * have nothing to do with mail. Doing the lookup by hand is what makes the
     * dependency OPTIONAL, and optionality is the entire point of the fallback
     * above.
     */
    private static Session lookupSession(String jndiName) throws NamingException {
        InitialContext context = new InitialContext();
        try {
            return (Session) context.lookup(jndiName);
        } finally {
            // An InitialContext holds resources in some providers. Closing it is
            // cheap insurance and costs one line.
            try {
                context.close();
            } catch (NamingException ignored) {
                // Nothing useful to do, and it must not mask the real outcome.
            }
        }
    }
}
