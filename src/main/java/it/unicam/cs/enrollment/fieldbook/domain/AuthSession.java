package it.unicam.cs.enrollment.fieldbook.domain;

import it.unicam.cs.enrollment.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One logged-in browser. Created at login, deleted at logout, and swept when it
 * expires.
 *
 * <h2>Why an opaque token in the database and not a JWT</h2>
 * A JWT is a signed statement the server does not have to remember. That
 * property is exactly what makes it good for a fleet of stateless services and
 * bad for a first-party browser session:
 *
 * <ul>
 *   <li><b>You cannot revoke it.</b> "Log out everywhere" and "this account was
 *       compromised, kill its sessions" both become "wait for it to expire",
 *       unless you add a denylist - at which point you have a session table
 *       again, but a worse one.</li>
 *   <li><b>You must manage a signing key.</b> Rotating it invalidates every
 *       token; leaking it forges every token.</li>
 *   <li><b>It tempts you to put data in it.</b> Claims are readable by anyone
 *       holding the token and stale the moment the underlying row changes.</li>
 * </ul>
 *
 * <p>A random opaque token, looked up in a table, gives revocation for free and
 * costs one indexed primary-key-shaped lookup per request. The database is
 * already on the critical path of every request in this application; one more
 * hit on a small hot table is not what will make it slow.
 *
 * <p>The JWT answer becomes right when several independently deployed services
 * must all verify the caller without sharing a database. That is a real and
 * common situation - it is just not this one. Being able to say WHY you chose
 * one is the interview answer; naming a favourite is not.
 *
 * <h2>Why the token is hashed before it is stored</h2>
 * The raw token is a bearer credential: whoever holds it is the user. If it
 * were stored as-is, anyone who could read this table - a leaked backup, a SQL
 * injection hole, an over-broad support query - could impersonate every logged
 * in learner. Storing SHA-256 of the token means the table holds something that
 * cannot be replayed.
 *
 * <p>Plain SHA-256 rather than PBKDF2 is correct here and would be badly wrong
 * for a password. The difference is entropy: this token is 32 random bytes, so
 * there is no dictionary to try and no reason to make each guess expensive. A
 * password is a human word; that is the entire reason password hashing has a
 * cost factor. Applying the same tool to both is the mistake, in either
 * direction.
 */
@Entity
@Table(
        name = "fieldbook_sessions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_fieldbook_sessions_token",
                columnNames = "token_hash"),
        indexes = {
                @Index(name = "idx_fb_sessions_account", columnList = "account_id"),
                @Index(name = "idx_fb_sessions_expires", columnList = "expires_at")
        }
)
@NamedQuery(
        name = "AuthSession.findByTokenHash",
        query = "SELECT s FROM AuthSession s JOIN FETCH s.account WHERE s.tokenHash = :tokenHash"
)
@NamedQuery(
        name = "AuthSession.deleteExpired",
        query = "DELETE FROM AuthSession s WHERE s.expiresAt < :now"
)
@NamedQuery(
        name = "AuthSession.deleteForAccount",
        query = "DELETE FROM AuthSession s WHERE s.account = :account"
)
public class AuthSession extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Thirty days. Long enough that a reader is not logged out mid-course. */
    public static final Duration LIFETIME = Duration.ofDays(30);

    /**
     * {@code ManyToOne} is the owning side and therefore holds the foreign key.
     * LAZY because most requests need the session only to answer "is this
     * token still valid" - but note that the named query above uses
     * {@code JOIN FETCH}, because the authentication filter DOES need the
     * account and would otherwise fire a second SELECT on every request. Lazy
     * by default, fetched deliberately where the use case needs it: that pair
     * is the whole of JPA fetching strategy.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fb_sessions_account"))
    private LearnerAccount account;

    /** SHA-256 of the raw token, hex encoded. 64 characters, always. */
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Truncated on the way in. A user agent string is attacker-controlled input
     * that ends up on a "your active sessions" screen, so it is length-limited
     * at the boundary rather than trusted to be sensible.
     */
    @Column(name = "user_agent", length = 200)
    private String userAgent;

    protected AuthSession() {
        // required by JPA
    }

    private AuthSession(LearnerAccount account, String tokenHash, Instant expiresAt, String userAgent) {
        this.account = account;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
    }

    public static AuthSession issue(LearnerAccount account, String tokenHash,
                                    Instant now, String userAgent) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(now, "now must not be null");
        String ua = userAgent == null ? null
                : userAgent.substring(0, Math.min(userAgent.length(), 200));
        return new AuthSession(account, tokenHash, now.plus(LIFETIME), ua);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * Push the expiry back out to a full lifetime from now.
     *
     * <p>Called on use, so that somebody who reads the fieldbook every week is
     * never logged out, while an abandoned session still dies on schedule. The
     * cost is an UPDATE, which is why the service only does it once the session
     * is more than a day old rather than on every request.
     */
    public void extend(Instant now) {
        this.expiresAt = now.plus(LIFETIME);
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

    public String getUserAgent() {
        return userAgent;
    }
}
