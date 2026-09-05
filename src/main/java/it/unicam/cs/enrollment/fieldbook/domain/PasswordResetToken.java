package it.unicam.cs.enrollment.fieldbook.domain;

import it.unicam.cs.enrollment.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One outstanding "I forgot my password" request.
 *
 * <h2>The shape of a reset, and why every step of it is here</h2>
 * A password reset is a SECOND authentication channel. For as long as one of
 * these rows is alive, possession of an inbox is as good as knowing the
 * password - so the row is deliberately weaker than a session in every
 * dimension that matters:
 *
 * <ul>
 *   <li><b>It is short-lived.</b> {@link #LIFETIME} is one hour, against a
 *       session's thirty days. The window in which a forwarded email, a shared
 *       screen or a mail archive is a credential should be measured in the time
 *       it takes somebody to read their mail, not in weeks.</li>
 *   <li><b>It is single use.</b> {@link #consume} stamps {@link #usedAt}, and
 *       a stamped row is refused. Mail is copied, quoted and archived; a link
 *       that works twice works for whoever ends up holding the second copy.</li>
 *   <li><b>Only its hash is stored.</b> Exactly like {@code AuthSession}: the
 *       raw token goes in the email and never touches a table, so a stolen
 *       backup yields hashes and a hash cannot be put in a URL. See
 *       {@code TokenMint} for why this hash is fast and a password hash is
 *       slow.</li>
 * </ul>
 *
 * <h2>Why used rows are kept rather than deleted</h2>
 * Consuming a token stamps it instead of deleting it, and the nightly sweep
 * removes it later. The difference shows up in the one conversation that
 * matters: somebody says their password changed without their asking, and the
 * question is whether a reset was requested, when, and whether it was used. A
 * deleted row answers none of that. Keeping evidence of a security-relevant
 * action for longer than the action itself is worth the storage every time.
 *
 * <h2>Why a token and not a code the user retypes</h2>
 * A six-digit code has a million possibilities, which is small enough to be
 * guessed by anybody willing to make requests, so a code-based flow needs its
 * own rate limit to be worth anything at all. This token is 256 bits from a
 * CSPRNG and has nothing to guess. Codes exist for a real reason - they survive
 * being read aloud, and they work when the link is opened on a different
 * device - and neither reason applies here.
 */
@Entity
@Table(
        name = "fieldbook_password_resets",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_fieldbook_resets_token",
                columnNames = "token_hash")
)
/*
 * JOIN FETCH for the same reason AuthSession's lookup has one: the caller
 * always goes on to change that account's password, so the second SELECT is
 * certain rather than merely likely.
 */
@NamedQuery(
        name = "PasswordResetToken.findByTokenHash",
        query = "SELECT t FROM PasswordResetToken t JOIN FETCH t.account WHERE t.tokenHash = :tokenHash"
)
public class PasswordResetToken extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * One hour.
     *
     * <p>Long enough to survive a mail server that queues for a few minutes and
     * a person who reads their inbox after lunch; short enough that a link left
     * in a webmail client overnight is already dead. Fifteen minutes is
     * defensible and generates support tickets; a day is not defensible.
     */
    public static final Duration LIFETIME = Duration.ofHours(1);

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fb_resets_account"))
    private LearnerAccount account;

    /** SHA-256 of the raw token, hex encoded. 64 characters, always. */
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** When it was spent. Null while the token is still usable. */
    @Column(name = "used_at")
    private Instant usedAt;

    /**
     * The address the request came from, for the same reason a session stores a
     * user agent: it is the only thing that makes an audit line useful after
     * the fact. Length-limited because it is input.
     */
    @Column(name = "requested_from", length = 60)
    private String requestedFrom;

    protected PasswordResetToken() {
        // required by JPA
    }

    private PasswordResetToken(LearnerAccount account, String tokenHash,
                               Instant expiresAt, String requestedFrom) {
        this.account = account;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.requestedFrom = requestedFrom;
    }

    public static PasswordResetToken issue(LearnerAccount account, String tokenHash,
                                           Instant now, String requestedFrom) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(now, "now must not be null");
        String from = requestedFrom == null ? null
                : requestedFrom.substring(0, Math.min(requestedFrom.length(), 60));
        return new PasswordResetToken(account, tokenHash, now.plus(LIFETIME), from);
    }

    /**
     * Whether this token may still be spent.
     *
     * <p>One method rather than an {@code isExpired} and an {@code isUsed} the
     * caller has to remember to check both of. A predicate that can be
     * half-applied is a predicate somebody will half-apply.
     */
    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    /**
     * Spend it.
     *
     * <p>Returns false if it was already spent, so the caller can treat a
     * double submit as the no-op it is rather than as a second reset. The check
     * and the stamp are in the same method for the usual reason: separated,
     * they are a check-then-act race, and the transaction is the only thing
     * making this one safe.
     */
    public boolean consume(Instant now) {
        if (!isUsable(now)) {
            return false;
        }
        this.usedAt = now;
        return true;
    }

    public LearnerAccount getAccount() {
        return account;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public String getRequestedFrom() {
        return requestedFrom;
    }
}
