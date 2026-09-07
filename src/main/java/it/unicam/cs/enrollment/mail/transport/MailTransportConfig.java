package it.unicam.cs.enrollment.mail.transport;

import it.unicam.cs.enrollment.mail.MailConfig;
import jakarta.mail.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.Properties;

/**
 * Chooses which {@link MailTransport} the application runs with, and creates it.
 *
 * <h2>Why a {@code @Bean} method rather than two components</h2>
 * Spring injects by TYPE. Two classes implement {@code MailTransport}, so
 * annotating both {@code @Component} would make every injection point ambiguous
 * and startup would fail with {@code NoUniqueBeanDefinitionException} -
 * correctly, because the container genuinely cannot guess which one is meant.
 *
 * <p>The standard answers are {@code @Primary} (one wins by default),
 * {@code @Qualifier} (chosen at every injection point),
 * {@code @ConditionalOnProperty} (chosen by configuration), and a plain
 * {@code @Bean} method (chosen once, in code, at runtime). A {@code @Bean}
 * method is right here because the decision depends on something a condition
 * cannot express well: three modes, one of which inspects the configuration and
 * degrades with an explanation.
 *
 * <p>Note also that neither implementation carries {@code @Component}. That is
 * deliberate: they are ordinary classes, so they cannot be picked up by
 * component scanning by accident, and this method is the only way to get one.
 * Deciding what is NOT a bean is part of designing with a container.
 *
 * <h2>One instance, shared</h2>
 * A {@code @Bean} method runs once and its result is a singleton, which is why
 * {@link MailTransport} documents that implementations must be thread-safe.
 *
 * <h2>Why this does not use {@code JavaMailSender}</h2>
 * Spring Boot's {@code spring-boot-starter-mail} auto-configures a
 * {@code JavaMailSender} from {@code spring.mail.*}, and in a normal project
 * that is what you would inject. This code talks to {@code jakarta.mail}
 * directly for one reason: the fieldbook's mail chapter is about what a mail
 * send actually IS - a MIME message, a session, a transport, and a list of
 * failure modes worth telling apart. A helper that hides all four teaches none
 * of them. Use {@code JavaMailSender} in real work.
 */
@Configuration
public class MailTransportConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MailTransportConfig.class);

    @Bean
    public MailTransport mailTransport(MailConfig config) {
        switch (config.getTransportMode()) {
            case LOG:
                LOG.info("Mail transport: log only (enrollment.mail.transport=log)");
                return new LoggingMailTransport("configured with transport=log");

            case SMTP:
                // The mode says SMTP, so a missing host is a configuration
                // error, not something to paper over. Failing at STARTUP means
                // whoever deployed it finds out immediately, instead of a
                // student finding out three days later that no mail ever went.
                return new SmtpMailTransport(session(config), config, config.describeSmtpTarget());

            case AUTO:
            default:
                return autoSelect(config);
        }
    }

    /**
     * Use SMTP if a host is configured; otherwise say so, loudly, and carry on.
     *
     * <h3>The judgement call</h3>
     * Degrading to a fake transport is normally a bad habit: the system reports
     * success while doing nothing, which is how a "working" deployment silently
     * sends no mail for a week. It is the right default HERE because the primary
     * audience is a reader running this on a laptop, and an application that
     * refuses to start because there is no SMTP server on their machine teaches
     * them nothing about Spring Boot.
     *
     * <p>The mitigations are what make it defensible: the fallback is logged at
     * WARN with the reason, the mailbox API reports which transport is live, and
     * {@code transport=smtp} turns the degradation off for any environment that
     * cares. A fallback nobody can see is the dangerous kind; this one announces
     * itself in three places.
     */
    private MailTransport autoSelect(MailConfig config) {
        Optional<String> host = config.getSmtpHost();
        if (host.isPresent()) {
            LOG.info("Mail transport: SMTP via {}", config.describeSmtpTarget());
            return new SmtpMailTransport(session(config), config, config.describeSmtpTarget());
        }
        LOG.warn("No SMTP host configured (enrollment.mail.smtp-host) - falling back to the "
                + "log-only transport. No email will actually be sent. Start the Mailpit "
                + "container (docker compose up -d mailpit) or set "
                + "enrollment.mail.transport=smtp to make this a startup failure instead.");
        return new LoggingMailTransport("no SMTP host configured");
    }

    /**
     * Builds the {@code jakarta.mail.Session}.
     *
     * <p>A {@code Session} is a bag of properties plus, optionally, an
     * {@code Authenticator}. The property NAMES are the part worth knowing,
     * because they are the same strings in every JavaMail-based stack and
     * because a typo in one is silently ignored - a session with
     * {@code mail.smtp.hosts} set does not fail, it just tries to reach
     * {@code localhost}.
     */
    private Session session(MailConfig config) {
        String host = config.getSmtpHost().orElseThrow(() -> new IllegalStateException(
                "enrollment.mail.transport=smtp but enrollment.mail.smtp-host is not set. "
                        + "Set it, or use transport=auto/log."));

        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.isSmtpStartTls()));
        // Fail rather than hang. A mail server that accepts the connection and
        // then says nothing would otherwise hold the dispatcher thread for as
        // long as the OS allows, which on some systems is minutes.
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        Optional<String> username = config.getSmtpUsername();
        if (username.isEmpty()) {
            props.put("mail.smtp.auth", "false");
            return Session.getInstance(props);
        }

        props.put("mail.smtp.auth", "true");
        String user = username.get();
        String password = config.getSmtpPassword().orElse("");
        return Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override
            protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                return new jakarta.mail.PasswordAuthentication(user, password);
            }
        });
    }
}
