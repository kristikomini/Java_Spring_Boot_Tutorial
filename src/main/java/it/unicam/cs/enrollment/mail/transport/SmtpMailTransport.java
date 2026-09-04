package it.unicam.cs.enrollment.mail.transport;

import it.unicam.cs.enrollment.mail.MailConfig;
import it.unicam.cs.enrollment.mail.domain.MailMessage;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.util.Date;

/**
 * Delivery over SMTP, using the {@code jakarta.mail.Session} the application
 * server provides.
 *
 * <h2>Where the SMTP host actually comes from</h2>
 * Not from here. The session is configured in the server
 * ({@code docker/wildfly/configure.cli} defines an outbound socket binding and
 * a mail session bound to {@code java:jboss/mail/Enrollment}) and looked up by
 * name at runtime. Exactly the same indirection as the datasource in
 * {@code persistence.xml}, for exactly the same reason: host names and
 * credentials are an operational concern, and the identical WAR has to run in
 * every environment.
 *
 * <h2>What this class is careful about</h2>
 * <ul>
 *   <li><b>Header injection.</b> Everything that ends up in a header is
 *       stripped of CR and LF first. A subject line containing {@code "\r\nBcc:
 *       everyone@..."} would otherwise become a real Bcc header - the mail
 *       equivalent of SQL injection, and the reason a rendered value must never
 *       be concatenated into a protocol without being sanitised for it.</li>
 *   <li><b>Charset.</b> UTF-8 is stated explicitly for the subject, the body
 *       and the display names. Left to the platform default, a message
 *       containing "Universit&agrave;" comes out mangled on some servers and
 *       fine on others, which is the worst kind of bug to reproduce.</li>
 *   <li><b>Failure classification.</b> See {@link #classify}. Getting this
 *       wrong is what turns a queue into a hot loop.</li>
 * </ul>
 *
 * <h2>One connection per message</h2>
 * {@code Transport.send} opens a connection, sends, and closes it. That is a
 * TCP handshake plus a TLS handshake plus an SMTP greeting per email - fine for
 * the volume this application produces, wasteful at thousands per minute. The
 * fix, when it is needed, is {@code session.getTransport("smtp")} held open
 * across a batch. Mentioned rather than done, because premature connection
 * pooling is how a mail bug becomes a connection-lifecycle bug.
 */
public class SmtpMailTransport implements MailTransport {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpMailTransport.class);

    private static final String UTF_8 = "UTF-8";

    private final Session session;
    private final MailConfig config;
    private final String jndiName;

    public SmtpMailTransport(Session session, MailConfig config, String jndiName) {
        this.session = session;
        this.config = config;
        this.jndiName = jndiName;
    }

    @Override
    public void send(MailMessage message) throws MailDeliveryException {
        String recipient = config.getRedirectTo().orElse(message.getRecipient().getValue());

        try {
            MimeMessage mime = new MimeMessage(session);

            mime.setFrom(new InternetAddress(
                    config.getFromAddress(), headerSafe(config.getFromName()), UTF_8));
            mime.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
            mime.setSubject(headerSafe(message.getSubject()), UTF_8);
            mime.setSentDate(new Date());
            mime.setText(message.getBody(), UTF_8);

            // Not required, and genuinely useful: an operator staring at a
            // message in the relay's queue can tie it back to a row in our
            // outbox without asking anybody.
            message.getTemplateKey().ifPresent(key -> setHeaderQuietly(mime, "X-Mail-Template", key));
            message.getDedupeKey().ifPresent(key -> setHeaderQuietly(mime, "X-Mail-Key", key));

            if (config.getRedirectTo().isPresent()) {
                setHeaderQuietly(mime, "X-Original-To", message.getRecipient().getValue());
                LOG.debug("Redirecting mail for {} to {}",
                        message.getRecipient().getValue(), recipient);
            }

            Transport.send(mime);

            LOG.debug("Delivered '{}' to {} via {}", message.getSubject(), recipient, jndiName);

        } catch (UnsupportedEncodingException e) {
            // UTF-8 is required of every JVM, so this cannot happen. It is
            // caught rather than ignored because "cannot happen" and "is not
            // declared" are different claims, and only the compiler checks one.
            throw MailDeliveryException.permanent("UTF-8 unavailable in this JVM", e);
        } catch (MessagingException e) {
            throw classify(e, recipient);
        }
    }

    /**
     * Decide whether a failure is worth another attempt.
     *
     * <h3>The cases, in the order they matter</h3>
     * <ul>
     *   <li>{@code AddressException} - the address is not a valid address. No
     *       amount of waiting fixes a typo. PERMANENT.</li>
     *   <li>{@code SendFailedException} with invalid recipients - the server
     *       said "no such mailbox" (a 5xx). PERMANENT. But the same exception
     *       type is also thrown with only VALID-UNSENT addresses when the server
     *       deferred the message (a 4xx), and that one is worth retrying, which
     *       is why the invalid list is inspected rather than the class name.</li>
     *   <li>{@code AuthenticationFailedException} - our credentials are wrong.
     *       Treated as transient on purpose: it is a configuration error a human
     *       will fix within the retry window, and dropping the mail would punish
     *       the student for an operations mistake.</li>
     *   <li>Everything else - connection refused, timeouts, TLS trouble, the
     *       relay restarting. TRANSIENT, which is the right default: retrying
     *       something undeliverable costs a few attempts, while giving up on
     *       something deliverable loses the message for good.</li>
     * </ul>
     */
    private MailDeliveryException classify(MessagingException e, String recipient) {
        String summary = e.getClass().getSimpleName() + ": " + e.getMessage();

        if (e instanceof AddressException) {
            return MailDeliveryException.permanent(
                    "Address " + recipient + " is not a valid email address - " + summary, e);
        }

        if (e instanceof SendFailedException) {
            SendFailedException failure = (SendFailedException) e;
            if (failure.getInvalidAddresses() != null && failure.getInvalidAddresses().length > 0) {
                return MailDeliveryException.permanent(
                        "Server rejected recipient " + recipient + " - " + summary, e);
            }
            return MailDeliveryException.transientFailure(
                    "Server deferred the message for " + recipient + " - " + summary, e);
        }

        if (e instanceof AuthenticationFailedException) {
            return MailDeliveryException.transientFailure(
                    "SMTP authentication failed - check the mail session credentials - " + summary, e);
        }

        return MailDeliveryException.transientFailure(summary, e);
    }

    /**
     * Strips the characters that could end one header and start another.
     *
     * <p>Jakarta Mail encodes non-ASCII subjects safely on its own, but a
     * defence that lives at the point where untrusted text meets a
     * line-delimited protocol is a defence you can still see when the calling
     * code is rewritten. Templates render names supplied by users; assume they
     * are hostile.
     */
    private static String headerSafe(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[\\r\\n]", " ").trim();
    }

    /** Headers are a nicety; failing to set one must never fail the send. */
    private static void setHeaderQuietly(MimeMessage mime, String name, String value) {
        try {
            mime.setHeader(name, headerSafe(value));
        } catch (MessagingException e) {
            LOG.debug("Could not set header {}", name, e);
        }
    }

    @Override
    public String describe() {
        return "SMTP via " + jndiName;
    }
}
