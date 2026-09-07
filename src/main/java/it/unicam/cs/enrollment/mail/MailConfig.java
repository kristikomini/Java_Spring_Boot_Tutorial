package it.unicam.cs.enrollment.mail;

import it.unicam.cs.enrollment.mail.domain.RetryPolicy;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Every knob the mail subsystem has, resolved once at startup.
 *
 * <h2>Configuration is not source code</h2>
 * The SMTP host, the from-address and "are we actually allowed to send" differ
 * between a laptop, CI, staging and production. Baking them into a class means
 * a rebuild to change environment, and a code review to change an operational
 * decision - which is the wrong person deciding at the wrong time. The
 * twelve-factor rule is the one worth remembering: CONFIG LIVES IN THE
 * ENVIRONMENT, code is identical everywhere.
 *
 * <h2>Two sources, in a deliberate order</h2>
 * <ol>
 *   <li>a JVM system property - {@code -Denrollment.mail.from=...}, which is
 *       what you reach for when debugging one server;</li>
 *   <li>an environment variable - {@code ENROLLMENT_MAIL_FROM}, which is what
 *       Docker, Kubernetes and CI actually set.</li>
 * </ol>
 * The property wins, because the more specific and more temporary source should
 * override the ambient one. The translation between the two spellings is
 * mechanical: lower-case dots become upper-case underscores.
 *
 * <p>Spring Boot has a much better answer for this, and you should normally use
 * it: {@code @ConfigurationProperties(prefix = "enrollment.mail")} binds a whole
 * class of fields from {@code application.yml}, environment variables, command
 * line arguments and four other sources, with type conversion, validation and
 * IDE completion - and RELAXED BINDING, which is why
 * {@code enrollment.mail.from} and {@code ENROLLMENT_MAIL_FROM} are the same
 * property without anybody writing the translation.
 *
 * <p>It is done by hand HERE so that the mechanism is visible rather than
 * annotation-shaped: twenty lines of {@code System.getProperty} with a fallback
 * is all that any config library is, underneath. Read this class once, then use
 * {@code @ConfigurationProperties} forever - {@code NotificationClient} shows
 * the ordinary way with {@code @Value}, and the {@code resilience4j} block in
 * {@code application.yml} shows what properly bound configuration looks like.
 *
 * <h2>Read once, at startup</h2>
 * A singleton bean resolving in {@code @PostConstruct} means a change to the
 * environment needs a restart. That is a real limitation and a deliberate one:
 * configuration that can change under a running request is configuration two
 * halves of one operation can disagree about. Where live reconfiguration is
 * genuinely needed, it belongs in a database table with an audit trail, not in
 * a re-read of the environment. (Spring Cloud Config plus
 * {@code @RefreshScope} is the framework answer, and it carries exactly that
 * caveat.)
 */
@Component
public class MailConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MailConfig.class);

    private static final String PREFIX = "enrollment.mail.";

    /** How a transport is chosen. See {@code MailTransportConfig}. */
    public enum TransportMode {
        /** Use SMTP if a host is actually configured, otherwise log. */
        AUTO,
        /** Insist on SMTP; fail loudly at startup if no host is set. */
        SMTP,
        /** Never open a socket - write the message to the server log. */
        LOG
    }

    private boolean enabled;
    private TransportMode transportMode;
    private String smtpHost;
    private int smtpPort;
    private String smtpUsername;
    private String smtpPassword;
    private boolean smtpStartTls;
    private String fromAddress;
    private String fromName;
    private String subjectPrefix;
    private String redirectTo;
    private String publicBaseUrl;
    private int maxAttempts;
    private int batchSize;
    private Duration stuckAfter;
    private int retentionDays;

    @PostConstruct
    void resolve() {
        this.enabled = booleanValue("enabled", true);
        this.transportMode = enumValue("transport", TransportMode.AUTO);
        this.smtpHost = stringValue("smtp-host", null);
        this.smtpPort = intValue("smtp-port", 1025, 1, 65535);
        this.smtpUsername = stringValue("smtp-username", null);
        this.smtpPassword = stringValue("smtp-password", null);
        this.smtpStartTls = booleanValue("smtp-starttls", false);
        this.fromAddress = stringValue("from", "no-reply@enrollment.unicam.test");
        this.fromName = stringValue("from-name", "UNICAM Enrollment");
        this.subjectPrefix = stringValue("subject-prefix", "");
        this.redirectTo = stringValue("redirect-to", null);
        this.publicBaseUrl = stringValue("public-base-url", null);
        this.maxAttempts = intValue("max-attempts", 5, 1, 20);
        this.batchSize = intValue("batch-size", 25, 1, 500);
        this.stuckAfter = Duration.ofMinutes(intValue("stuck-after-minutes", 10, 1, 1440));
        this.retentionDays = intValue("retention-days", 30, 1, 3650);

        // Logging the resolved configuration at startup is a small habit with a
        // large payoff: "which from-address is this environment using?" becomes
        // a question the log already answered, instead of an archaeology
        // exercise across three repositories. Note that nothing secret is
        // printed - a password would be logged as its presence, never its value.
        LOG.info("Mail configuration: enabled={} transport={} smtp={} auth={} from={} <{}> "
                        + "redirectTo={} publicBaseUrl={} maxAttempts={} batchSize={} retentionDays={}",
                enabled, transportMode,
                smtpHost == null ? "(not configured)" : smtpHost + ":" + smtpPort,
                smtpUsername == null ? "none" : "yes",
                fromName, fromAddress,
                redirectTo == null ? "(none)" : redirectTo,
                publicBaseUrl == null ? "(from the request)" : publicBaseUrl,
                maxAttempts, batchSize, retentionDays);

        if (publicBaseUrl == null) {
            LOG.warn("enrollment.mail.public-base-url is not set: links in outgoing mail will be "
                    + "built from the request that triggered them. Fine behind a proxy that "
                    + "overwrites Host; set it explicitly in production. See getPublicBaseUrl().");
        }

        if (redirectTo != null) {
            LOG.warn("Mail REDIRECT is active: every message will be delivered to {} "
                    + "regardless of its recipient. This must never be set in production.", redirectTo);
        }
    }

    // ------------------------------------------------------------------
    // Resolution helpers
    // ------------------------------------------------------------------

    private static Optional<String> lookup(String key) {
        String property = System.getProperty(PREFIX + key);
        if (property != null && !property.trim().isEmpty()) {
            return Optional.of(property.trim());
        }
        String envKey = (PREFIX + key).toUpperCase(Locale.ROOT)
                .replace('.', '_')
                .replace('-', '_');
        String env = System.getenv(envKey);
        if (env != null && !env.trim().isEmpty()) {
            return Optional.of(env.trim());
        }
        return Optional.empty();
    }

    private static String stringValue(String key, String fallback) {
        return lookup(key).orElse(fallback);
    }

    private static boolean booleanValue(String key, boolean fallback) {
        return lookup(key).map(Boolean::parseBoolean).orElse(fallback);
    }

    /**
     * Bounded, and loud about a value it cannot use.
     *
     * <p>A typo in {@code BATCH_SIZE} should not silently become 0 (a dispatcher
     * that never sends anything) or 100000 (one that loads the whole table into
     * memory). Clamping into a documented range, and saying so, is friendlier
     * than either crashing the deployment or obeying nonsense.
     */
    private static int intValue(String key, int fallback, int min, int max) {
        Optional<String> raw = lookup(key);
        if (!raw.isPresent()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.get());
            if (parsed < min || parsed > max) {
                LOG.warn("{}{}={} is outside [{}, {}] - using {}", PREFIX, key, parsed, min, max, fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException e) {
            LOG.warn("{}{}='{}' is not a number - using {}", PREFIX, key, raw.get(), fallback);
            return fallback;
        }
    }

    private static <E extends Enum<E>> E enumValue(String key, E fallback) {
        Optional<String> raw = lookup(key);
        if (!raw.isPresent()) {
            return fallback;
        }
        try {
            @SuppressWarnings("unchecked")
            Class<E> type = (Class<E>) fallback.getClass();
            return Enum.valueOf(type, raw.get().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOG.warn("{}{}='{}' is not one of {} - using {}",
                    PREFIX, key, raw.get(), fallback.getClass().getSimpleName(), fallback);
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    /**
     * The master switch. When false, messages are still QUEUED - the outbox row
     * is still written - but the dispatcher does not drain them.
     *
     * <p>That asymmetry is on purpose. A kill switch that also stopped the
     * queuing would lose the evidence that mail was owed; this way, turning it
     * back on delivers the backlog.
     */
    public boolean isEnabled() {
        return enabled;
    }

    public TransportMode getTransportMode() {
        return transportMode;
    }

    /**
     * The SMTP host, or empty when none is configured.
     *
     * <p>{@code Optional} rather than a nullable getter, because "there is no
     * mail server here" is the NORMAL case on a laptop and the transport
     * selection in {@code MailTransportConfig} has to branch on it. A getter
     * that returns null invites a caller to forget.
     */
    public Optional<String> getSmtpHost() {
        return Optional.ofNullable(smtpHost).filter(h -> !h.trim().isEmpty());
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public Optional<String> getSmtpUsername() {
        return Optional.ofNullable(smtpUsername).filter(u -> !u.trim().isEmpty());
    }

    /**
     * The SMTP password.
     *
     * <p>Note that it is read like any other value and never logged - see the
     * startup line above, which reports only whether authentication is
     * configured. A password in a log file is a password in whatever system
     * ships your logs, and that system almost certainly has weaker access
     * control than this one.
     */
    public Optional<String> getSmtpPassword() {
        return Optional.ofNullable(smtpPassword).filter(p -> !p.isEmpty());
    }

    public boolean isSmtpStartTls() {
        return smtpStartTls;
    }

    /** {@code host:port}, for log lines and the mailbox status endpoint. */
    public String describeSmtpTarget() {
        return getSmtpHost().map(h -> h + ":" + smtpPort).orElse("(not configured)");
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public String getFromName() {
        return fromName;
    }

    /**
     * Prepended to every subject - {@code "[STAGING]"} earns its keep the first
     * time someone panics about an email that turns out to be from a test
     * environment. {@code MailService} adds the separating space, so the value
     * configured here does not have to carry trailing whitespace through a
     * shell.
     */
    public String getSubjectPrefix() {
        return subjectPrefix;
    }

    /**
     * When set, every message goes here instead of to its real recipient.
     *
     * <p>This exists because of a mistake that is made regularly and is
     * unforgivable when it happens: a staging environment restored from a
     * production database dump, with real addresses in it, wired to a real SMTP
     * server. The first job that runs mails thousands of actual people. A
     * redirect in every non-production environment makes that impossible rather
     * than unlikely.
     */
    /**
     * The absolute base URL this application is reached at, for links inside
     * emails - {@code https://fieldbook.example.it/enrollment}, no trailing
     * slash.
     *
     * <h3>Why this is configuration and not something to work out per request</h3>
     * Because the obvious alternative is a genuine vulnerability with a name.
     * A link built from the incoming request takes its host from the
     * {@code Host} header, and that header is supplied by whoever made the
     * request. Send a password reset request with {@code Host: evil.example}
     * and the victim receives a real, valid reset link pointing at the
     * attacker's server - the mail is authentic, the token is authentic, and
     * the moment the victim clicks it the token is in somebody else's access
     * log. HOST HEADER INJECTION is the term, and password reset is where it is
     * nearly always found, because reset mail is the one place an application
     * puts a credential into a URL.
     *
     * <p>Empty means "build it from the request anyway", which is what makes
     * the fieldbook usable on a laptop where the host really is whatever you
     * typed. That is a development convenience with a startup warning attached,
     * not a default anybody should ship.
     */
    public Optional<String> getPublicBaseUrl() {
        return Optional.ofNullable(publicBaseUrl);
    }

    public Optional<String> getRedirectTo() {
        return Optional.ofNullable(redirectTo);
    }

    public RetryPolicy getRetryPolicy() {
        return RetryPolicy.withMaxAttempts(maxAttempts);
    }

    /** How many messages one dispatcher run will take on. */
    public int getBatchSize() {
        return batchSize;
    }

    /** After this long, a SENDING row is assumed abandoned and re-queued. */
    public Duration getStuckAfter() {
        return stuckAfter;
    }

    /** How long delivered messages are kept before the nightly purge. */
    public int getRetentionDays() {
        return retentionDays;
    }
}
