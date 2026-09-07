package it.unicam.cs.enrollment.fieldbook.api;

import it.unicam.cs.enrollment.fieldbook.api.dto.AccountResponse;
import it.unicam.cs.enrollment.fieldbook.api.dto.ChangePasswordRequest;
import it.unicam.cs.enrollment.fieldbook.api.dto.ForgotPasswordRequest;
import it.unicam.cs.enrollment.fieldbook.api.dto.LoginRequest;
import it.unicam.cs.enrollment.fieldbook.api.dto.RegisterRequest;
import it.unicam.cs.enrollment.fieldbook.api.dto.ResetPasswordRequest;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.fieldbook.security.Authenticated;
import it.unicam.cs.enrollment.fieldbook.security.CsrfProtected;
import it.unicam.cs.enrollment.fieldbook.security.CurrentUser;
import it.unicam.cs.enrollment.fieldbook.security.PasswordHasher;
import it.unicam.cs.enrollment.fieldbook.security.SessionCookies;
import it.unicam.cs.enrollment.fieldbook.service.AccountService;
import it.unicam.cs.enrollment.mail.MailConfig;
import it.unicam.cs.enrollment.web.dto.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.ArrayList;

/**
 * Sign up, sign in, sign out.
 *
 * <h2>Why every method here goes through a {@code char[]}</h2>
 * The password arrives as a {@code String} - Jackson gives you no choice - and
 * is converted immediately, used, and wiped. That does not undo the fact that
 * the {@code String} existed, so it is a partial measure and is described as
 * one. What it does buy is that the plaintext is not still sitting in a live
 * object graph while the rest of the request runs, which is the window a heap
 * dump or an exception serialiser would catch it in.
 *
 * <h2>Status codes</h2>
 * <ul>
 *   <li>201 on registration, with the new resource described in the body.</li>
 *   <li>401 for bad credentials - not 403. 401 means "I do not know who you
 *       are"; 403 means "I know who you are and you may not". Using 403 for a
 *       failed login tells the caller the credentials were recognised.</li>
 *   <li>409 when a username or an address is already taken. The request was
 *       well formed and the state of the world refused it, which is exactly
 *       what 409 is for.</li>
 *   <li>202 for a password reset request, always - see
 *       {@link #forgotPassword}. It is the one status here that is chosen for
 *       what it does NOT reveal.</li>
 *   <li>429 when throttled, with {@code Retry-After} carrying the REAL
 *       remaining window rather than a constant. A client that retries politely
 *       needs to be told how long to wait, and a client that does not is being
 *       told anyway. Registration is throttled as well as login - it is
 *       anonymous, it writes a row and it runs a full PBKDF2, which is the
 *       exact shape of an endpoint that needs a limit.</li>
 * </ul>
 *
 * <h2>{@code HttpServletRequest} as a parameter, not a field</h2>
 * A {@code @RestController} is a SINGLETON: one instance serves every request
 * on every thread. Anything request-specific held in a field is therefore a
 * data race waiting for concurrency, and it is a mistake that behaves perfectly
 * until the day two people use the application at once. Spring MVC will resolve
 * a great many things as handler-method parameters instead - the request, the
 * session, headers, the principal - and taking them there is what keeps the
 * singleton safe.
 */
@RestController
@RequestMapping(path = "/api/fieldbook/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@CsrfProtected
public class AuthController {

    private final AccountService accounts;
    private final CurrentUser currentUser;
    private final MailConfig mailConfig;

    public AuthController(AccountService accounts, CurrentUser currentUser, MailConfig mailConfig) {
        this.accounts = accounts;
        this.currentUser = currentUser;
        this.mailConfig = mailConfig;
    }

    /**
     * The caller address, for the per-source throttles.
     *
     * <h3>Why this does not read {@code X-Forwarded-For} itself</h3>
     * That is the documented shape of the value, and it is also
     * ATTACKER-CONTROLLED: a header is whatever the caller typed, so an attacker
     * sends a different one on every request and a per-source limit keyed on it
     * never fires once. A rate limiter that can be bypassed by setting a header
     * is not a rate limiter, it is a log line.
     *
     * <p>The fix is not to parse the header more cleverly - taking the last
     * entry instead of the first, say - because no parsing rule can tell which
     * entries a trusted proxy wrote and which the caller supplied. The fix is
     * to get the answer from something the caller cannot write, and to make the
     * proxy responsible for putting the truth there.
     *
     * <p>In Spring Boot that is {@code server.forward-headers-strategy:
     * framework} in {@code application.yml}. With it, a filter consumes
     * {@code X-Forwarded-For} and {@code X-Forwarded-Proto} at the edge and
     * rewrites the request itself, so {@code getRemoteAddr()} returns the real
     * client address online and the peer address locally. One value, correct in
     * both places, and not writable by the caller.
     *
     * <p>The cost of getting this wrong runs in the other direction too: behind
     * a proxy WITHOUT that setting, every request appears to come from the
     * proxy, and the per-source counter throttles the whole internet as one
     * caller. That failure is at least loud and safe. The header-trusting
     * version failed silently and open, which is the worse of the two.
     */
    private static String sourceAddress(HttpServletRequest request) {
        String peer = request == null ? null : request.getRemoteAddr();
        return (peer == null || peer.trim().isEmpty()) ? "unknown" : peer.trim();
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> register(@Valid @RequestBody RegisterRequest body,
                                           HttpServletRequest request) {
        char[] password = body.getPassword().toCharArray();
        try {
            AccountService.Login login = accounts.register(
                    body.getUsername(),
                    body.getEmail(),
                    body.getDisplayName(),
                    password,
                    body.getTimeZone(),
                    sourceAddress(request),
                    request.getHeader(HttpHeaders.USER_AGENT));
            return respondTo(login, HttpStatus.CREATED, request);
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> login(@Valid @RequestBody LoginRequest body,
                                        HttpServletRequest request) {
        char[] password = body.getPassword().toCharArray();
        try {
            AccountService.Login login = accounts.login(
                    body.getUsername(),
                    password,
                    sourceAddress(request),
                    request.getHeader(HttpHeaders.USER_AGENT));
            return respondTo(login, HttpStatus.OK, request);
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    private ResponseEntity<Object> respondTo(AccountService.Login login, HttpStatus okStatus,
                                             HttpServletRequest request) {
        switch (login.getResult()) {
            case OK:
                ResponseCookie cookie = SessionCookies.issue(login.getRawToken(), request);
                return ResponseEntity.status(okStatus)
                        .header(HttpHeaders.SET_COOKIE, cookie.toString())
                        .body(describe(login.getAccount()));
            case THROTTLED:
                // The real remaining window, not a constant. A header that says
                // "fifteen minutes" to somebody with twenty seconds left is
                // worse than no header: a polite client obeys it, so the number
                // is not advice, it is the wait.
                long retryAfter = login.getRetryAfterSeconds();
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter))
                        .body(problem(request, HttpStatus.TOO_MANY_REQUESTS, "Too many attempts",
                                "Too many attempts from this address. Try again in "
                                        + describe(retryAfter) + "."));
            case BAD_CREDENTIALS:
            default:
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(problem(request, HttpStatus.UNAUTHORIZED, "Sign-in failed",
                                "That username and password combination was not recognised."));
        }
    }

    @GetMapping("/me")
    @Authenticated
    public AccountResponse me() {
        return describe(currentUser.require());
    }

    @PostMapping("/logout")
    @Authenticated
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        accounts.logout(currentUser.session().orElse(null));
        // The cookie is expired in the response as well as the row deleted.
        // Deleting only the row leaves the browser sending a dead cookie
        // forever, which works but means every request pays a pointless lookup.
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, SessionCookies.expire(request).toString())
                .build();
    }

    @PostMapping("/logout-all")
    @Authenticated
    public ResponseEntity<Void> logoutEverywhere(HttpServletRequest request) {
        accounts.logoutEverywhere(currentUser.require());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, SessionCookies.expire(request).toString())
                .build();
    }

    @PostMapping(path = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Authenticated
    public ResponseEntity<Object> changePassword(@Valid @RequestBody ChangePasswordRequest body,
                                                 HttpServletRequest request) {
        char[] current = body.getCurrentPassword().toCharArray();
        char[] replacement = body.getNewPassword().toCharArray();
        try {
            boolean changed = accounts.changePassword(currentUser.require(), current, replacement);
            if (!changed) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(problem(request, HttpStatus.UNAUTHORIZED, "Sign-in failed",
                                "The current password was not correct."));
            }
            // Every session is now revoked, this one included, so the cookie
            // must go too - otherwise the page looks signed in and every
            // subsequent request is a 401.
            return ResponseEntity.noContent()
                    .header(HttpHeaders.SET_COOKIE, SessionCookies.expire(request).toString())
                    .build();
        } finally {
            PasswordHasher.wipe(current);
            PasswordHasher.wipe(replacement);
        }
    }

    /**
     * Step one of a reset: "email me a link".
     *
     * <h3>Why this always answers 202, and never anything else</h3>
     * {@code 202 Accepted} means "I have taken your request and will act on it
     * separately", which is a true description of queueing mail - and, more to
     * the point, it is the same answer for an address that has an account and
     * one that does not. Any status that distinguishes the two turns this
     * endpoint into a membership oracle offered anonymously to anybody. The
     * body says so in as many words, because a page that cannot promise a mail
     * is coming should not imply that one is.
     *
     * <p>Note the shape of that decision: the service already refuses to reveal
     * anything, and the controller has to refuse as well. Neither layer can do
     * it alone - a service that returns a boolean makes it very hard for the
     * controller not to leak it, which is why {@code requestPasswordReset}
     * returns nothing at all.
     *
     * <p>Not {@code @Authenticated}, obviously. It is also not throttled by
     * {@code LoginThrottle}, because that counts FAILURES and this cannot fail;
     * the limit that matters here is on how many emails one address can be sent
     * and it lives in the service, next to the table it counts.
     */
    @PostMapping(path = "/password/forgot", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> forgotPassword(@Valid @RequestBody ForgotPasswordRequest body,
                                                 HttpServletRequest request) {
        accounts.requestPasswordReset(
                body.getEmail(),
                resetLinkBase(request),
                sourceAddress(request));

        return ResponseEntity.accepted()
                .body(problem(request, HttpStatus.ACCEPTED, "Check your inbox",
                        "If that address has an account, a reset link is on its way. "
                                + "It is valid for one hour and can be used once."));
    }

    /**
     * Step two of a reset: "here is the link, and my new password".
     *
     * <h3>Why this does not sign you in afterwards</h3>
     * It would be convenient, and it would mean that possession of one email is
     * enough to end up holding a session - so the blast radius of a forwarded
     * message grows from "could have changed the password" to "is now logged
     * in". Making somebody type the password they just chose also happens to be
     * the only proof that they typed what they meant to.
     *
     * <p>204 rather than 200 with a body: there is nothing to describe. The
     * page knows what it asked for, and every session including the caller's
     * has just been revoked, so there is no account to return.
     */
    @PostMapping(path = "/password/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> resetPassword(@Valid @RequestBody ResetPasswordRequest body,
                                                HttpServletRequest request) {
        char[] replacement = body.getNewPassword().toCharArray();
        try {
            AccountService.ResetResult result =
                    accounts.resetPassword(body.getToken(), replacement);

            if (result != AccountService.ResetResult.OK) {
                // 410 Gone, not 400. The request was perfectly well formed; the
                // thing it referred to has expired or has already been used,
                // which is what 410 says and 400 does not. One status for all
                // three failures - unknown, expired, spent - because telling
                // them apart tells a caller which tokens exist.
                return ResponseEntity.status(HttpStatus.GONE)
                        .body(problem(request, HttpStatus.GONE, "That link no longer works",
                                "Reset links can be used once and expire after an hour. "
                                        + "Ask for a new one."));
            }

            // The caller may well have been signed in on this browser, and
            // every session was just revoked - so the cookie goes with them,
            // exactly as it does after a password change.
            return ResponseEntity.noContent()
                    .header(HttpHeaders.SET_COOKIE, SessionCookies.expire(request).toString())
                    .build();
        } finally {
            PasswordHasher.wipe(replacement);
        }
    }

    /**
     * The absolute URL of the page that hosts the reset form.
     *
     * <p>Configuration first, the request only as a fallback. Building it from
     * the request means taking the host from the {@code Host} header, which the
     * caller controls - and a reset link pointing at an attacker's host is
     * HOST HEADER INJECTION, the classic way this exact feature is attacked.
     * {@code MailConfig.getPublicBaseUrl} carries the full argument and warns at
     * startup when it is unset.
     *
     * <p>{@code fromContextPath} is the application root, so
     * {@code http://host:8280/enrollment}. The page sits directly under it,
     * which is the same relationship the page itself relies on when it resolves
     * {@code api/} against its own URL.
     */
    private String resetLinkBase(HttpServletRequest request) {
        return mailConfig.getPublicBaseUrl()
                .map(base -> base.endsWith("/") ? base + "tutorial.html" : base + "/tutorial.html")
                .orElseGet(() -> ServletUriComponentsBuilder.fromContextPath(request)
                        .path("/tutorial.html")
                        .toUriString());
    }

    /**
     * Delete the account and everything in it.
     *
     * <p>Present because a tool that stores your study history and offers no
     * way out is a tool you should not sign in to. It is also the least
     * expensive part of taking data protection seriously, and the one most
     * often skipped.
     */
    @DeleteMapping("/me")
    @Authenticated
    public ResponseEntity<Void> deleteAccount(HttpServletRequest request) {
        accounts.deleteAccount(currentUser.require());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, SessionCookies.expire(request).toString())
                .build();
    }

    /**
     * "about 4 minutes", for the sentence a person reads.
     *
     * <p>The header carries the exact number for machines; this rounds for
     * humans, because "try again in 247 seconds" invites arithmetic nobody
     * wants to do. Rounding UP, so that following the sentence never lands you
     * back on the same error.
     */
    private static String describe(long seconds) {
        if (seconds <= 60) {
            return "under a minute";
        }
        long minutes = (seconds + 59) / 60;
        return "about " + minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private AccountResponse describe(LearnerAccount account) {
        AccountResponse r = new AccountResponse();
        r.setId(account.getId());
        r.setUsername(account.getUsername().getValue());
        r.setEmail(account.getEmail().getValue());
        r.setDisplayName(account.getDisplayName());
        r.setRoles(new ArrayList<>(account.getRoles()));
        r.setCreatedAt(account.getCreatedAt());
        r.setLastSeenAt(account.getLastSeenAt());
        r.setStreak(accounts.currentStreak(account));
        r.setBestStreak(account.getBestStreak());
        return r;
    }

    private ProblemDetail problem(HttpServletRequest request, HttpStatus status,
                                  String title, String detail) {
        ProblemDetail p = new ProblemDetail();
        p.setType("about:blank");
        p.setTitle(title);
        p.setStatus(status.value());
        p.setDetail(detail);
        p.setInstance(request.getRequestURI());
        return p;
    }
}
