package it.unicam.cs.enrollment.fieldbook.service;

import it.unicam.cs.enrollment.common.logging.Loggable;
import it.unicam.cs.enrollment.domain.model.Email;
import it.unicam.cs.enrollment.exception.InvalidRequestException;
import it.unicam.cs.enrollment.fieldbook.domain.AuthSession;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.fieldbook.repository.AuthSessionRepository;
import it.unicam.cs.enrollment.fieldbook.repository.LearnerAccountRepository;
import it.unicam.cs.enrollment.fieldbook.repository.ProgressRepository;
import it.unicam.cs.enrollment.fieldbook.repository.StickyNoteRepository;
import it.unicam.cs.enrollment.fieldbook.security.LoginThrottle;
import it.unicam.cs.enrollment.fieldbook.security.PasswordHasher;
import it.unicam.cs.enrollment.fieldbook.security.TokenMint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Registration, login, logout and everything else that touches a credential.
 *
 * <h2>Where the security decisions actually live</h2>
 * The individual mechanisms are in the {@code security} package -
 * {@link PasswordHasher}, {@link TokenMint}, {@link LoginThrottle}. This class
 * is where they are composed, and composition is where most real security bugs
 * are: every piece correct, assembled in an order that leaks something.
 */
@Loggable
@ApplicationScoped
public class AccountService {

    /**
     * The shortest password accepted.
     *
     * <p>Twelve, with no composition rules - no "must contain a symbol". That
     * combination is the current NIST guidance and it reverses twenty years of
     * advice, for a reason worth being able to state: complexity rules do not
     * produce unpredictable passwords, they produce {@code Password1!}, and
     * they push people towards reuse and sticky notes. Length is what actually
     * buys entropy, so the rule is a floor on length and nothing else.
     */
    public static final int MIN_PASSWORD_LENGTH = 12;

    /** Re-extend a session at most once a day, rather than on every request. */
    private static final Duration EXTEND_AFTER = Duration.ofDays(1);

    /**
     * How stale "last seen" has to get before it is worth a write.
     *
     * <p>Updating it on every request is one extra UPDATE per request for a
     * value whose only consumer is a human glancing at a screen. Fifteen
     * minutes is precise enough for that and turns a write on the hot path into
     * a write nobody notices.
     */
    private static final Duration LAST_SEEN_GRANULARITY = Duration.ofMinutes(15);

    private LearnerAccountRepository accounts;
    private AuthSessionRepository sessions;
    private ProgressRepository progress;
    private StickyNoteRepository notes;
    private PasswordHasher hasher;
    private TokenMint mint;
    private LoginThrottle throttle;
    private Clock clock;
    private Logger log;

    /**
     * Constructor injection, and the {@link Clock} in particular.
     *
     * <p>Injecting the clock rather than calling {@code Instant.now()} is what
     * makes streaks and expiry testable: a test can hand this service a clock
     * fixed to next Tuesday and assert on what happens, instead of sleeping or
     * accepting that the test cannot cover it. Any code that reads the current
     * time from a static method has hidden a dependency it will later wish it
     * had declared.
     */
    /**
     * Required by CDI, and the reason is worth knowing because the error it
     * produces names neither this constructor nor the annotation that needs it.
     *
     * <p>An {@code @ApplicationScoped} bean is NORMAL SCOPED, so what gets
     * injected anywhere is never this object - it is a generated PROXY
     * subclass that forwards to the contextual instance. To generate that
     * subclass the container has to be able to instantiate it, and a subclass
     * can only be instantiated through a superclass constructor it can call
     * with no arguments.
     *
     * <p>Leave it out and the deployment fails, not the compile:
     * <pre>WELD-001435: Normal scoped bean class ... is not proxyable
     * because it has no no-args constructor</pre>
     * reported against whatever injected it rather than against this class.
     *
     * <p>It also forces the fields below to be non-final, since this
     * constructor leaves them unset. That is the cost of the proxy, it is why
     * every service in this codebase looks like this, and it is the concrete
     * form of the point the fieldbook makes about why the container avoids
     * your constructors.
     */
    protected AccountService() {
        // required by CDI
    }

    @Inject
    public AccountService(LearnerAccountRepository accounts,
                          AuthSessionRepository sessions,
                          ProgressRepository progress,
                          StickyNoteRepository notes,
                          PasswordHasher hasher,
                          TokenMint mint,
                          LoginThrottle throttle,
                          Clock clock,
                          Logger log) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.progress = progress;
        this.notes = notes;
        this.hasher = hasher;
        this.mint = mint;
        this.throttle = throttle;
        this.clock = clock;
        this.log = log;
    }

    /** What happened, without making the caller catch anything. */
    public enum LoginResult { OK, BAD_CREDENTIALS, THROTTLED }

    /** A login attempt's outcome, plus the raw token when there is one. */
    public static final class Login {
        private final LoginResult result;
        private final String rawToken;
        private final LearnerAccount account;

        Login(LoginResult result, String rawToken, LearnerAccount account) {
            this.result = result;
            this.rawToken = rawToken;
            this.account = account;
        }

        public LoginResult getResult() {
            return result;
        }

        public String getRawToken() {
            return rawToken;
        }

        public LearnerAccount getAccount() {
            return account;
        }

        public boolean isOk() {
            return result == LoginResult.OK;
        }
    }

    /**
     * Create an account and log it straight in.
     *
     * <h3>Why a duplicate email is not reported as one</h3>
     * The obvious implementation answers 409 "that address is already
     * registered", and that answer turns an open registration form into a
     * membership oracle: anybody can now enumerate which of a list of addresses
     * has an account here. For a study tool the stakes are low; the habit is
     * what matters, because the identical form on a medical or financial site
     * leaks something that genuinely harms people.
     *
     * <p>So a duplicate registration behaves like a login attempt with the
     * supplied password. Right password, you are in - which is what somebody
     * re-registering by accident actually wanted. Wrong password, you get the
     * same generic failure a wrong login gets. Nothing distinguishes "taken" from
     * "wrong".
     *
     * <p>The real product answer is to send an email either way and say nothing
     * in the response at all. That needs a mail server, and pretending otherwise
     * would be worse than saying so.
     */
    @Transactional
    public Login register(String rawEmail, String displayName, char[] password,
                          String timeZone, String sourceAddress, String userAgent) {
        validatePassword(password);
        Email email = parseEmail(rawEmail);
        Instant now = clock.instant();

        Optional<LearnerAccount> existing = accounts.findByEmail(email.getValue());
        if (existing.isPresent()) {
            log.debug("Registration for an existing address; falling through to a login attempt");
            return login(rawEmail, password, sourceAddress, userAgent);
        }

        String name = (displayName == null || displayName.trim().isEmpty())
                ? email.getValue().substring(0, email.getValue().indexOf('@'))
                : displayName;

        LearnerAccount account = LearnerAccount.register(
                email, name, hasher.hash(password), normaliseZone(timeZone));
        accounts.save(account);
        account.touch(now);

        log.info("Registered fieldbook account id={}", account.getId());
        return new Login(LoginResult.OK, openSession(account, now, userAgent), account);
    }

    /**
     * Check a password and, if it is right, start a session.
     *
     * <h3>The three things this method is careful about</h3>
     * <ol>
     *   <li><b>One error for two causes.</b> Unknown address and wrong password
     *       both return {@code BAD_CREDENTIALS}. Distinguishing them is the
     *       same enumeration leak as above, in its most common location.</li>
     *   <li><b>The hash runs even for an unknown address.</b> Returning early
     *       would make a miss measurably faster than a hit, and that timing
     *       difference is itself the oracle - you would have closed the front
     *       door and left the letterbox open. Hashing a throwaway value keeps
     *       both paths the same shape.</li>
     *   <li><b>The throttle is consulted first.</b> Otherwise the expensive
     *       hash is the thing an attacker gets to run at will.</li>
     * </ol>
     */
    @Transactional
    public Login login(String rawEmail, char[] password, String sourceAddress, String userAgent) {
        Instant now = clock.instant();
        String normalised;
        try {
            normalised = parseEmail(rawEmail).getValue();
        } catch (InvalidRequestException malformed) {
            // A malformed address is a failed attempt like any other, and is
            // counted like one: otherwise the throttle can be bypassed by
            // sending rubbish.
            throttle.recordFailure(rawEmail, sourceAddress, now);
            return new Login(LoginResult.BAD_CREDENTIALS, null, null);
        }

        if (!throttle.allow(normalised, sourceAddress, now)) {
            log.warn("Login throttled for source={}", sourceAddress);
            return new Login(LoginResult.THROTTLED, null, null);
        }

        Optional<LearnerAccount> found = accounts.findByEmail(normalised);
        if (!found.isPresent()) {
            // Deliberate work, not dead code: see (2) above. A compiler or a
            // reviewer will want to delete this line; the comment is why it
            // stays.
            hasher.matches(password, DUMMY_HASH);
            throttle.recordFailure(normalised, sourceAddress, now);
            return new Login(LoginResult.BAD_CREDENTIALS, null, null);
        }

        LearnerAccount account = found.get();
        if (!hasher.matches(password, account.getPasswordHash())) {
            throttle.recordFailure(normalised, sourceAddress, now);
            return new Login(LoginResult.BAD_CREDENTIALS, null, null);
        }

        // Right password: this is the only moment the plaintext exists, so it
        // is the only moment the stored hash can be silently upgraded to the
        // current cost factor.
        if (hasher.needsRehash(account.getPasswordHash())) {
            account.changePasswordHash(hasher.hash(password));
            log.info("Rehashed password for account id={} at the current cost", account.getId());
        }

        throttle.recordSuccess(normalised);
        account.touch(now);
        return new Login(LoginResult.OK, openSession(account, now, userAgent), account);
    }

    /**
     * A hash of a value nobody knows, used only to burn the same CPU time on a
     * miss as on a hit. Generated once at class load rather than per request,
     * because generating it per request would itself be a timing difference.
     */
    private static final String DUMMY_HASH =
            new PasswordHasher().hash("this password belongs to nobody".toCharArray());

    /**
     * Resolve a raw cookie value to a live session, extending it if it is
     * getting stale.
     *
     * <p>Returns empty for absent, unknown and expired alike - the caller has
     * no use for the distinction and every reason not to report it.
     */
    @Transactional
    public Optional<AuthSession> resolve(String rawToken) {
        if (rawToken == null || rawToken.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<AuthSession> found = sessions.findByTokenHash(mint.fingerprint(rawToken));
        if (!found.isPresent()) {
            return Optional.empty();
        }
        AuthSession session = found.get();
        if (session.isExpired(now)) {
            // Delete on discovery as well as on schedule. The scheduled sweep
            // is the safety net, not the mechanism.
            sessions.delete(session);
            return Optional.empty();
        }
        Instant renewAt = session.getExpiresAt().minus(AuthSession.LIFETIME).plus(EXTEND_AFTER);
        if (now.isAfter(renewAt)) {
            // Bulk update rather than a mutation of the managed entity, so two
            // overlapping requests cannot collide on the version column.
            sessions.extendTo(session.getId(), now.plus(AuthSession.LIFETIME));
        }

        LearnerAccount account = session.getAccount();
        Instant seen = account.getLastSeenAt();
        if (seen == null || Duration.between(seen, now).compareTo(LAST_SEEN_GRANULARITY) > 0) {
            accounts.touchLastSeen(account.getId(), now);
        }
        return Optional.of(session);
    }

    @Transactional
    public void logout(AuthSession session) {
        if (session != null) {
            sessions.delete(session);
        }
    }

    @Transactional
    public int logoutEverywhere(LearnerAccount account) {
        return sessions.deleteAllForAccount(account);
    }

    /**
     * Change a password, and invalidate every existing session in the same
     * transaction.
     *
     * <p>The second half is the part people forget. If old sessions survive a
     * password change then changing it after a compromise achieves nothing at
     * all, because the attacker was never using the password - they were using
     * the cookie.
     */
    @Transactional
    public boolean changePassword(LearnerAccount account, char[] current, char[] replacement) {
        if (!hasher.matches(current, account.getPasswordHash())) {
            return false;
        }
        validatePassword(replacement);
        account.changePasswordHash(hasher.hash(replacement));
        sessions.deleteAllForAccount(account);
        log.info("Password changed and all sessions revoked for account id={}", account.getId());
        return true;
    }

    /**
     * Record that this learner studied today, in their own timezone.
     *
     * <p>Returns true only on the day's first activity, which is what the page
     * turns into "day 6" appearing in the corner.
     */
    @Transactional
    public boolean touchStudyDay(LearnerAccount account) {
        // The account handed in came from the authentication filter and is
        // DETACHED - its transaction committed before the resource method ran.
        // Writing to a detached entity changes nothing, silently, so the
        // managed instance is looked up first. This is the persist/merge
        // lesson arriving in ordinary code rather than in an example.
        return accounts.findById(account.getId())
                .map(managed -> managed.recordStudyDay(today(managed)))
                .orElse(false);
    }

    /**
     * The streak, computed from a targeted query rather than from the account's
     * lazy collection - which would throw once the account is detached. See
     * {@link LearnerAccount#streakOf}.
     */
    @Transactional
    public int currentStreak(LearnerAccount account) {
        return LearnerAccount.streakOf(accounts.studyDaysFor(account.getId()), today(account));
    }

    /** Sorted study days, for the progress snapshot. Same reasoning. */
    @Transactional
    public java.util.List<LocalDate> studyDays(LearnerAccount account) {
        java.util.List<LocalDate> days = new java.util.ArrayList<>(
                accounts.studyDaysFor(account.getId()));
        java.util.Collections.sort(days);
        return days;
    }

    /**
     * Which calendar day it is for this learner, in their own timezone.
     *
     * <p>Public because {@code ProgressService} needs the same answer while
     * holding a row lock, and re-deriving it there would be two places that
     * could disagree about when a day ends - which is precisely the kind of
     * duplication that makes a streak wrong for people who study late.
     */
    public LocalDate todayFor(LearnerAccount account) {
        return today(account);
    }

    private LocalDate today(LearnerAccount account) {
        ZoneId zone;
        try {
            zone = account.getTimeZone() == null
                    ? clock.getZone()
                    : ZoneId.of(account.getTimeZone());
        } catch (RuntimeException unknownZone) {
            // The zone came from a browser and is therefore input. An
            // unrecognised one must not break the request; falling back to the
            // server zone costs at most an hour of edge case.
            zone = clock.getZone();
        }
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /**
     * Delete the account and everything attached to it.
     *
     * <p>Written by hand rather than left to {@code cascade = REMOVE} on the
     * associations. Cascading from an entity means the delete order is decided
     * by the provider, and it is easy to end up with a foreign key violation
     * that only appears once there is enough data. Doing it explicitly, in
     * dependency order, in one transaction, is longer and never surprises
     * anybody.
     */
    @Transactional
    public void deleteAccount(LearnerAccount account) {
        notes.deleteAllFor(account);
        progress.resetFor(account);
        sessions.deleteAllForAccount(account);
        accounts.delete(accounts.getReference(account.getId()));
        log.info("Deleted fieldbook account id={} and all of its data", account.getId());
    }

    private String openSession(LearnerAccount account, Instant now, String userAgent) {
        String raw = mint.mint();
        sessions.save(AuthSession.issue(account, mint.fingerprint(raw), now, userAgent));
        return raw;
    }

    private Email parseEmail(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new InvalidRequestException("EMAIL_REQUIRED", "An email address is required");
        }
        try {
            return Email.of(raw);
        } catch (RuntimeException invalid) {
            throw new InvalidRequestException("EMAIL_INVALID", "That does not look like an email address");
        }
    }

    private void validatePassword(char[] password) {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new InvalidRequestException("PASSWORD_TOO_SHORT",
                    "The password must be at least " + MIN_PASSWORD_LENGTH + " characters long");
        }
    }

    private static String normaliseZone(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return ZoneId.of(raw.trim()).getId();
        } catch (RuntimeException unknown) {
            return null;
        }
    }
}
