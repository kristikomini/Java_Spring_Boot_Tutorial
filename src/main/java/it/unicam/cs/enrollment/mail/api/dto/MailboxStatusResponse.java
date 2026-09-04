package it.unicam.cs.enrollment.mail.api.dto;

import java.util.Map;
import java.util.Set;

/**
 * The health of the mail subsystem in one object: what it is configured to do,
 * and what is actually in the queue.
 *
 * <h2>The question this endpoint exists to answer</h2>
 * "Why has nobody received anything?" The answer is almost always one of four
 * things, and all four are visible here: delivery is switched off, the transport
 * fell back to log-only, everything is sitting in PENDING because the dispatcher
 * is not running, or everything is DEAD with the same {@code lastError}.
 *
 * <p>An operational endpoint that reports the ACTIVE configuration - rather
 * than what a config file says somewhere - is worth building for anything with
 * a fallback in it. The gap between the two is exactly where the incident lives.
 */
public class MailboxStatusResponse {

    private boolean deliveryEnabled;
    private String transport;
    private String fromAddress;
    private String redirectTo;
    private int maxAttempts;
    private int batchSize;
    private int retentionDays;
    private Map<String, Long> counts;
    private Set<String> templates;

    public MailboxStatusResponse() {
    }

    public boolean isDeliveryEnabled() {
        return deliveryEnabled;
    }

    public void setDeliveryEnabled(boolean deliveryEnabled) {
        this.deliveryEnabled = deliveryEnabled;
    }

    /** Human description of the live transport, e.g. "SMTP via java:jboss/mail/Enrollment". */
    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    /** Non-null only when every message is being diverted to one address. */
    public String getRedirectTo() {
        return redirectTo;
    }

    public void setRedirectTo(String redirectTo) {
        this.redirectTo = redirectTo;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    /** Row counts per status, every status present even at zero. */
    public Map<String, Long> getCounts() {
        return counts;
    }

    public void setCounts(Map<String, Long> counts) {
        this.counts = counts;
    }

    public Set<String> getTemplates() {
        return templates;
    }

    public void setTemplates(Set<String> templates) {
        this.templates = templates;
    }
}
