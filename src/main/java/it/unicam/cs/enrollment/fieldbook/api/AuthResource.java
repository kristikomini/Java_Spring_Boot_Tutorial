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
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.util.ArrayList;

/**
 * Sign up, sign in, sign out.
 *
 * <h2>Why every method here goes through a {@code char[]}</h2>
 * The password arrives as a {@code String} - JSON-B gives you no choice - and
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
 *   <li>429 when throttled, with {@code Retry-After}. A client that retries
 *       politely needs to be told how long to wait, and a client that does not
 *       is being told anyway.</li>
 * </ul>
 */
@Path("/fieldbook/auth")
@RequestScoped
@CsrfProtected
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject
    AccountService accounts;

    @Inject
    CurrentUser currentUser;

    @Inject
    MailConfig mailConfig;

    @Context
    UriInfo uriInfo;

    @Context
    HttpHeaders headers;

    /**
     * The servlet request, injected for one value: {@code getRemoteAddr()}.
     *
     * <p>JAX-RS has no portable way to ask "who is on the other end of this
     * socket" - {@code UriInfo} describes the URL and {@code HttpHeaders}
     * describes what the caller chose to say about itself, which is not the
     * same question. Dropping to the servlet API for it is a deliberate,
     * documented step outside the abstraction, and the alternative is trusting
     * a header the caller controls.
     */
    @Context
    HttpServletRequest servletRequest;

    /**
     * The caller address, for the per-source throttle.
     *
     * <p>{@code X-Forwarded-For} is set by a reverse proxy and is
     * ATTACKER-CONTROLLED unless the proxy overwrites it. Trusting it blindly
     * means an attacker sends a different value on every request and the
     * per-source limit never fires. The correct configuration is a proxy that
     * replaces the header rather than appending to it, and an application that
     * takes the LAST entry rather than the first. This takes the first, which
     * is right behind a proxy you control and wrong behind one you do not -
     * written down here because a comment is cheaper than a false sense of
     * security.
     */
    private String sourceAddress(String forwarded, String remote) {
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        if (remote != null && !remote.trim().isEmpty()) {
            return remote.trim();
        }
        // The socket's own peer address, which is the only value here that the
        // caller cannot choose. Behind a proxy it is the proxy, which is why
        // the forwarded header is consulted first - and why the note above
        // about which end of that header to trust matters.
        String peer = servletRequest == null ? null : servletRequest.getRemoteAddr();
        return (peer == null || peer.isEmpty()) ? "unknown" : peer;
    }

    @POST
    @Path("/register")
    public Response register(@Valid @NotNull RegisterRequest request,
                             @HeaderParam("X-Forwarded-For") String forwarded) {
        char[] password = request.getPassword().toCharArray();
        try {
            AccountService.Login login = accounts.register(
                    request.getUsername(),
                    request.getEmail(),
                    request.getDisplayName(),
                    password,
                    request.getTimeZone(),
                    sourceAddress(forwarded, null),
                    headers.getHeaderString(HttpHeaders.USER_AGENT));
            return respondTo(login, Response.Status.CREATED);
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    @POST
    @Path("/login")
    public Response login(@Valid @NotNull LoginRequest request,
                          @HeaderParam("X-Forwarded-For") String forwarded) {
        char[] password = request.getPassword().toCharArray();
        try {
            AccountService.Login login = accounts.login(
                    request.getUsername(),
                    password,
                    sourceAddress(forwarded, null),
                    headers.getHeaderString(HttpHeaders.USER_AGENT));
            return respondTo(login, Response.Status.OK);
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    private Response respondTo(AccountService.Login login, Response.Status okStatus) {
        switch (login.getResult()) {
            case OK:
                return Response.status(okStatus)
                        .cookie(SessionCookies.issue(login.getRawToken(), uriInfo))
                        .entity(describe(login.getAccount()))
                        .build();
            case THROTTLED:
                return Response.status(429)
                        .header("Retry-After", 900)
                        .entity(problem(429, "Too many attempts",
                                "Too many failed sign-in attempts. Try again in a few minutes."))
                        .build();
            case BAD_CREDENTIALS:
            default:
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(problem(401, "Sign-in failed",
                                "That username and password combination was not recognised."))
                        .build();
        }
    }

    @GET
    @Path("/me")
    @Authenticated
    public AccountResponse me() {
        return describe(currentUser.require());
    }

    @POST
    @Path("/logout")
    @Authenticated
    public Response logout() {
        accounts.logout(currentUser.session().orElse(null));
        // The cookie is expired in the response as well as the row deleted.
        // Deleting only the row leaves the browser sending a dead cookie
        // forever, which works but means every request pays a pointless lookup.
        return Response.noContent()
                .cookie(SessionCookies.expire(uriInfo))
                .build();
    }

    @POST
    @Path("/logout-all")
    @Authenticated
    public Response logoutEverywhere() {
        accounts.logoutEverywhere(currentUser.require());
        return Response.noContent()
                .cookie(SessionCookies.expire(uriInfo))
                .build();
    }

    @POST
    @Path("/password")
    @Authenticated
    public Response changePassword(@Valid @NotNull ChangePasswordRequest request) {
        char[] current = request.getCurrentPassword().toCharArray();
        char[] replacement = request.getNewPassword().toCharArray();
        try {
            boolean changed = accounts.changePassword(currentUser.require(), current, replacement);
            if (!changed) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(problem(401, "Sign-in failed",
                                "The current password was not correct."))
                        .build();
            }
            // Every session is now revoked, this one included, so the cookie
            // must go too - otherwise the page looks signed in and every
            // subsequent request is a 401.
            return Response.noContent()
                    .cookie(SessionCookies.expire(uriInfo))
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
     * anything, and the resource has to refuse as well. Neither layer can do it
     * alone - a service that returns a boolean makes it very hard for the
     * resource not to leak it, which is why {@code requestPasswordReset}
     * returns nothing at all.
     *
     * <p>Not {@code @Authenticated}, obviously. It is also not throttled by
     * {@code LoginThrottle}, because that counts FAILURES and this cannot fail;
     * the limit that matters here is on how many emails one address can be sent
     * and it lives in the service, next to the table it counts.
     */
    @POST
    @Path("/password/forgot")
    public Response forgotPassword(@Valid @NotNull ForgotPasswordRequest request,
                                   @HeaderParam("X-Forwarded-For") String forwarded) {
        accounts.requestPasswordReset(
                request.getEmail(),
                resetLinkBase(),
                sourceAddress(forwarded, null));

        return Response.accepted()
                .entity(problem(202, "Check your inbox",
                        "If that address has an account, a reset link is on its way. "
                                + "It is valid for one hour and can be used once."))
                .build();
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
    @POST
    @Path("/password/reset")
    public Response resetPassword(@Valid @NotNull ResetPasswordRequest request) {
        char[] replacement = request.getNewPassword().toCharArray();
        try {
            AccountService.ResetResult result =
                    accounts.resetPassword(request.getToken(), replacement);

            if (result != AccountService.ResetResult.OK) {
                // 410 Gone, not 400. The request was perfectly well formed; the
                // thing it referred to has expired or has already been used,
                // which is what 410 says and 400 does not. One status for all
                // three failures - unknown, expired, spent - because telling
                // them apart tells a caller which tokens exist.
                return Response.status(Response.Status.GONE)
                        .entity(problem(410, "That link no longer works",
                                "Reset links can be used once and expire after an hour. "
                                        + "Ask for a new one."))
                        .build();
            }

            // The caller may well have been signed in on this browser, and
            // every session was just revoked - so the cookie goes with them,
            // exactly as it does after a password change.
            return Response.noContent()
                    .cookie(SessionCookies.expire(uriInfo))
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
     * <p>{@code getBaseUri()} is the JAX-RS application root, so
     * {@code .../enrollment/api/}. The page sits one level up beside it, which
     * is what {@code resolve("../tutorial.html")} says - and is the same
     * relationship the page itself relies on when it resolves {@code api/}
     * against its own URL.
     */
    private String resetLinkBase() {
        return mailConfig.getPublicBaseUrl()
                .map(base -> base.endsWith("/") ? base + "tutorial.html" : base + "/tutorial.html")
                .orElseGet(() -> {
                    URI base = uriInfo.getBaseUri();
                    return base.resolve("../tutorial.html").toString();
                });
    }

    /**
     * Delete the account and everything in it.
     *
     * <p>Present because a tool that stores your study history and offers no
     * way out is a tool you should not sign in to. It is also the least
     * expensive part of taking data protection seriously, and the one most
     * often skipped.
     */
    @DELETE
    @Path("/me")
    @Authenticated
    public Response deleteAccount() {
        accounts.deleteAccount(currentUser.require());
        return Response.noContent()
                .cookie(SessionCookies.expire(uriInfo))
                .build();
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

    private it.unicam.cs.enrollment.api.dto.response.ProblemDetail problem(
            int status, String title, String detail) {
        it.unicam.cs.enrollment.api.dto.response.ProblemDetail p =
                new it.unicam.cs.enrollment.api.dto.response.ProblemDetail();
        p.setType("about:blank");
        p.setTitle(title);
        p.setStatus(status);
        p.setDetail(detail);
        p.setInstance(uriInfo.getPath());
        return p;
    }
}
