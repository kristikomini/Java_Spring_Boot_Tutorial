package it.unicam.cs.enrollment.fieldbook.service;

import it.unicam.cs.enrollment.common.logging.Loggable;
import it.unicam.cs.enrollment.domain.model.Email;
import it.unicam.cs.enrollment.exception.DuplicateResourceException;
import it.unicam.cs.enrollment.exception.InvalidRequestException;
import it.unicam.cs.enrollment.fieldbook.domain.AuthSession;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.fieldbook.domain.PasswordResetToken;
import it.unicam.cs.enrollment.fieldbook.domain.Username;
import it.unicam.cs.enrollment.fieldbook.repository.AuthSessionRepository;
import it.unicam.cs.enrollment.fieldbook.repository.LearnerAccountRepository;
import it.unicam.cs.enrollment.fieldbook.repository.PasswordResetTokenRepository;
import it.unicam.cs.enrollment.fieldbook.repository.ProgressRepository;
import it.unicam.cs.enrollment.fieldbook.repository.StickyNoteRepository;
import it.unicam.cs.enrollment.fieldbook.security.LoginThrottle;
import it.unicam.cs.enrollment.fieldbook.security.PasswordHasher;
import it.unicam.cs.enrollment.fieldbook.security.TokenMint;
import it.unicam.cs.enrollment.mail.service.MailService;
import it.unicam.cs.enrollment.mail.service.MailTemplates;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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

    /**
     * How many reset emails one account can cause in {@link #RESET_REQUEST_WINDOW}.
     *
     * <p>Deliberately generous - somebody who does not receive the first mail
     * will click again, and refusing them is worse than sending a third copy.
     * The number exists to bound the OTHER case: an anonymous endpoint that
     * sends mail on demand is a way of using this application to deliver
     * unwanted email to a stranger, and the only thing stopping it is a
     * counter. Note that this is the one throttle that protects a third party
     * rather than the service.
     */
    private static final int MAX_RESETS_PER_WINDOW = 5;

    /** The window the counter above applies to. */
    private static final Duration RESET_REQUEST_WINDOW = Duration.ofHours(1);

    /**
     * How long a spent or expired reset row is kept before the nightly sweep
     * removes it. It is evidence, not state - see {@link PasswordResetToken}.
     */
    public static final Duration RESET_AUDIT_WINDOW = Duration.ofDays(30);

    private LearnerAccountRepository accounts;
    private AuthSessionRepository sessions;
    private PasswordResetTokenRepository resets;
    private ProgressRepository progress;
    private StickyNoteRepository notes;
    private MailService mail;
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
                          PasswordResetTokenRepository resets,
                          ProgressRepository progress,
                          StickyNoteRepository notes,
                          MailService mail,
                          PasswordHasher hasher,
                          TokenMint mint,
                          LoginThrottle throttle,
                          Clock clock,
                          Logger log) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.resets = resets;
        this.progress = progress;
        this.notes = notes;
        this.mail = mail;
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
     * <h3>What this refuses out loud, and why that changed</h3>
     * Both collisions are reported: "that username is taken" and "that address
     * already has an account", each as a 409.
     *
     * <p>The second one is a reversal, and it is worth understanding rather
     * than merely noticing. While the ADDRESS was the login name, refusing a
     * duplicate turned an anonymous form into a membership oracle - post a list
     * of addresses, read off which ones have accounts here - so the old code
     * quietly attempted a login instead and never said the word "taken".
     *
     * <p>Two things changed with the username. The first is mechanical: a
     * duplicate address no longer implies anything about the credentials being
     * offered, because the username in the same request may well be free, so
     * there is no login to fall through to. The second is that the form now has
     * two distinguishable ways to fail, and a registration form that refuses
     * without saying which is a form nobody can get past.
     *
     * <p>What that costs is real and is not hidden: this endpoint will confirm
     * whether an address has an account here. Where that genuinely matters - a
     * medical or a financial site - the answer is to accept the registration,
     * send a verification mail, and say nothing in the response either way.
     * That needs an email verification step, which this project still does not
     * have. The anti-enumeration effort moved to where it now belongs and where
     * it is airtight: {@link #requestPasswordReset}, which behaves identically
     * for an address it knows and one it has never seen.
     */
    @Transactional
    public Login register(String rawUsername, String rawEmail, String displayName,
                          char[] password, String timeZone,
                          String sourceAddress, String userAgent) {
        validatePassword(password);
        Username username = parseUsername(rawUsername);
        Email email = parseEmail(rawEmail);
        Instant now = clock.instant();

        if (accounts.existsByUsername(username.getValue())) {
            throw new DuplicateResourceException(
                    "That username is already taken. Pick another one.");
        }
        if (accounts.existsByEmail(email.getValue())) {
            throw new DuplicateResourceException(
                    "That email address already has an account. Sign in, or reset the password.");
        }

        String name = (displayName == null || displayName.trim().isEmpty())
                ? username.getValue()
                : displayName;

        LearnerAccount account = LearnerAccount.register(
                username, email, name, hasher.hash(password), normaliseZone(timeZone));
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
     *   <li><b>One error for two causes.</b> Unknown username and wrong
     *       password both return {@code BAD_CREDENTIALS}. Distinguishing them
     *       is an enumeration leak in its most common location, and it is why a
     *       malformed handle is answered with the same sentence rather than
     *       with a helpful description of the rule.</li>
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
    public Login login(String rawUsername, char[] password, String sourceAddress, String userAgent) {
        Instant now = clock.instant();
        String normalised;
        try {
            normalised = parseUsername(rawUsername).getValue();
        } catch (InvalidRequestException malformed) {
            // A malformed handle is a failed attempt like any other, and is
            // counted like one: otherwise the throttle can be bypassed by
            // sending rubbish.
            throttle.recordFailure(rawUsername, sourceAddress, now);
            return new Login(LoginResult.BAD_CREDENTIALS, null, null);
        }

        if (!throttle.allow(normalised, sourceAddress, now)) {
            log.warn("Login throttled for source={}", sourceAddress);
            return new Login(LoginResult.THROTTLED, null, null);
        }

        Optional<LearnerAccount> found = accounts.findByUsername(normalised);
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
     * Step one of a reset: mint a token and email a link, or do nothing, and
     * never say which.
     *
     * <h3>The one rule this method exists to obey</h3>
     * Every path through it produces the SAME outcome as far as the caller can
     * tell. Unknown address, malformed address, address that belongs to
     * somebody who has asked five times in the last hour - all of them return
     * quietly, and the resource answers 202 regardless. That is not politeness;
     * an endpoint that answers differently for a known and an unknown address
     * is a public API for "does this person have an account here", offered
     * anonymously, to anybody. It is the single most common way an application
     * leaks its membership list.
     *
     * <p>Note what that costs, because it is the reason this design is not
     * free: somebody who mistypes their address gets silence and no mail, and
     * has no way to tell the difference from a mail that is merely slow. The
     * page says so explicitly rather than pretending the request succeeded.
     *
     * <h3>Why the previous requests are invalidated first</h3>
     * Otherwise clicking the button three times leaves three live links for an
     * hour, and the OLDEST one - the copy most likely to have been forwarded,
     * quoted or archived - is as good as the newest. "The newest request wins"
     * is what a person means when they ask again.
     *
     * <h3>Why the mail is queued rather than sent</h3>
     * {@code MailService.enqueue} is a database write that joins THIS
     * transaction. So the token row and the promise to send it either both
     * commit or both disappear; there is no state of the world in which a link
     * was emailed for a token that was rolled back. Sending it inline would
     * also mean an SMTP timeout on the request thread, and a password reset
     * that hangs for thirty seconds is a password reset people click four
     * times.
     *
     * @param pageBaseUrl absolute URL of the page that hosts the reset form.
     *                    Passed in rather than built here because a service has
     *                    no business knowing about HTTP - and because where
     *                    that value comes from is a security decision in its
     *                    own right, made in {@code AuthResource.resetLinkBase}.
     */
    @Transactional
    public void requestPasswordReset(String rawEmail, String pageBaseUrl, String sourceAddress) {
        Instant now = clock.instant();

        String normalised;
        try {
            normalised = parseEmail(rawEmail).getValue();
        } catch (InvalidRequestException malformed) {
            log.debug("Password reset requested for a malformed address; answering as usual");
            return;
        }

        Optional<LearnerAccount> found = accounts.findByEmail(normalised);
        if (!found.isPresent()) {
            // Logged at DEBUG, and the log line does NOT contain the address.
            // A log that records every address somebody guessed at is the same
            // membership list, written down somewhere with weaker access
            // control than the database.
            log.debug("Password reset requested for an address with no account");
            return;
        }

        LearnerAccount account = found.get();
        if (resets.countIssuedSince(account, now.minus(RESET_REQUEST_WINDOW)) >= MAX_RESETS_PER_WINDOW) {
            log.warn("Password reset rate limit reached for account id={}", account.getId());
            return;
        }

        resets.invalidateAllFor(account, now);

        String raw = mint.mint();
        PasswordResetToken token = resets.save(
                PasswordResetToken.issue(account, mint.fingerprint(raw), now, sourceAddress));

        Map<String, String> model = new LinkedHashMap<>();
        model.put("displayName", account.getDisplayName());
        model.put("username", account.getUsername().getValue());
        model.put("resetLink", resetLink(pageBaseUrl, raw));
        model.put("validFor", humanDuration(PasswordResetToken.LIFETIME));
        model.put("expiresAt", stamp(token.getExpiresAt(), account));
        model.put("requestedFrom", sourceAddress == null ? "an unrecorded address" : sourceAddress);

        // The dedupe key is the token id, so it is unique by construction. It
        // is set at all because enqueue() is idempotent only when there is a
        // key, and a retried transaction that queued the mail twice would send
        // two links for one request.
        mail.enqueueTemplate(MailTemplates.PASSWORD_RESET,
                account.getEmail().getValue(),
                account.getDisplayName(),
                model,
                "password-reset:" + token.getId());

        log.info("Queued a password reset link for account id={}", account.getId());
    }

    /** What {@link #resetPassword} did, without making the caller catch anything. */
    public enum ResetResult {
        /** The password was changed and every session revoked. */
        OK,
        /** No such token, or it was expired, or it had already been spent. */
        INVALID_TOKEN
    }

    /**
     * Step two of a reset: spend the token and set the new password.
     *
     * <h3>Why the three failures are one answer</h3>
     * Unknown, expired and already-used all return {@code INVALID_TOKEN}. The
     * distinction would be genuinely useful to a person whose link has gone
     * stale - and it is also a way of asking the server which tokens exist,
     * which is why the page says all three possibilities out loud instead and
     * offers the "send me another" button either way.
     *
     * <h3>The order of operations, which is the whole method</h3>
     * <ol>
     *   <li>Validate the new password FIRST. Consuming the token before
     *       checking the password means a too-short password burns the link,
     *       and the person now needs a second email to fix a typo.</li>
     *   <li>Consume the token. Single use, enforced inside the entity so the
     *       check and the stamp cannot be separated.</li>
     *   <li>Change the hash.</li>
     *   <li>Revoke every session, and every OTHER outstanding reset token.
     *       Both matter for the same reason: the reason people reset a password
     *       is that somebody else may have had it, and an attacker who is
     *       already holding a session cookie was never using the password
     *       anyway.</li>
     * </ol>
     * All four are in one transaction, so there is no moment at which the
     * password has changed and the old sessions are still alive.
     */
    @Transactional
    public ResetResult resetPassword(String rawToken, char[] newPassword) {
        validatePassword(newPassword);
        if (rawToken == null || rawToken.trim().isEmpty()) {
            return ResetResult.INVALID_TOKEN;
        }

        Instant now = clock.instant();
        Optional<PasswordResetToken> found = resets.findByTokenHash(mint.fingerprint(rawToken.trim()));
        if (!found.isPresent()) {
            return ResetResult.INVALID_TOKEN;
        }

        PasswordResetToken token = found.get();
        if (!token.consume(now)) {
            return ResetResult.INVALID_TOKEN;
        }

        LearnerAccount account = token.getAccount();
        account.changePasswordHash(hasher.hash(newPassword));
        sessions.deleteAllForAccount(account);
        resets.invalidateAllFor(account, now);

        log.info("Password reset completed and all sessions revoked for account id={}",
                account.getId());
        return ResetResult.OK;
    }

    /**
     * Housekeeping for the nightly job: drop reset rows that are past the audit
     * window. Called by {@code FieldbookMaintenanceJob}.
     */
    @Transactional
    public int sweepExpiredResets() {
        return resets.deleteOlderThan(clock.instant().minus(RESET_AUDIT_WINDOW));
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
        // Reset tokens hold a foreign key to the account, so they go before it
        // and not after. This is the line the explicit ordering above exists to
        // make obvious: with cascade = REMOVE it would be invisible, and its
        // absence would be a constraint violation that only appears once
        // somebody has actually used the reset form.
        resets.deleteAllFor(account);
        accounts.delete(accounts.getReference(account.getId()));
        log.info("Deleted fieldbook account id={} and all of its data", account.getId());
    }

    private String openSession(LearnerAccount account, Instant now, String userAgent) {
        String raw = mint.mint();
        sessions.save(AuthSession.issue(account, mint.fingerprint(raw), now, userAgent));
        return raw;
    }

    /**
     * Turn the handle typed into a form into a {@link Username}.
     *
     * <p>The two failures produce two different messages because they reach a
     * person in two different places. Registration shows them and they are
     * useful there. Sign-in never shows them - {@link #login} catches this
     * exception and answers with its single generic sentence - which is why the
     * wording here can afford to be specific.
     */
    private Username parseUsername(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new InvalidRequestException("USERNAME_REQUIRED", "A username is required");
        }
        try {
            return Username.of(raw);
        } catch (RuntimeException invalid) {
            throw new InvalidRequestException("USERNAME_INVALID",
                    "A username is " + Username.MIN_LENGTH + " to " + Username.MAX_LENGTH
                            + " characters: letters, digits, dot, underscore or hyphen, "
                            + "starting and ending with a letter or a digit");
        }
    }

    /**
     * Glue the token onto the page URL.
     *
     * <p>{@code account=reset} is what tells the page to open the reset panel;
     * the token rides in the query string beside it because a link in an email
     * is the only way to carry it, and an email client cannot POST. That is the
     * one place this token is exposed in a URL, and it is why it is single use
     * and lives for an hour - a browser history entry, a referrer header and a
     * proxy log all see it.
     */
    private static String resetLink(String pageBaseUrl, String rawToken) {
        String base = (pageBaseUrl == null || pageBaseUrl.trim().isEmpty())
                ? "tutorial.html" : pageBaseUrl.trim();
        String separator = base.indexOf('?') >= 0 ? "&" : "?";
        return base + separator + "account=reset&token="
                + java.net.URLEncoder.encode(rawToken, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** "60 minutes", for the mail body. Templates have no logic, so this does. */
    private static String humanDuration(Duration d) {
        long minutes = d.toMinutes();
        if (minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours == 1 ? "1 hour" : hours + " hours";
        }
        return minutes + " minutes";
    }

    /** The expiry, written in the learner's own timezone rather than the server's. */
    private String stamp(Instant when, LearnerAccount account) {
        ZoneId zone;
        try {
            zone = account.getTimeZone() == null ? clock.getZone() : ZoneId.of(account.getTimeZone());
        } catch (RuntimeException unknownZone) {
            zone = clock.getZone();
        }
        return DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm z", Locale.ENGLISH)
                .format(ZonedDateTime.ofInstant(when, zone));
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
