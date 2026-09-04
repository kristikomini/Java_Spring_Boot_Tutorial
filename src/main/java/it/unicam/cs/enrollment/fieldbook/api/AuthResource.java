package it.unicam.cs.enrollment.fieldbook.api;

import it.unicam.cs.enrollment.fieldbook.api.dto.AccountResponse;
import it.unicam.cs.enrollment.fieldbook.api.dto.ChangePasswordRequest;
import it.unicam.cs.enrollment.fieldbook.api.dto.LoginRequest;
import it.unicam.cs.enrollment.fieldbook.api.dto.RegisterRequest;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.fieldbook.security.Authenticated;
import it.unicam.cs.enrollment.fieldbook.security.CsrfProtected;
import it.unicam.cs.enrollment.fieldbook.security.CurrentUser;
import it.unicam.cs.enrollment.fieldbook.security.PasswordHasher;
import it.unicam.cs.enrollment.fieldbook.security.SessionCookies;
import it.unicam.cs.enrollment.fieldbook.service.AccountService;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
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

    @Context
    UriInfo uriInfo;

    @Context
    HttpHeaders headers;

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
        return remote == null ? "unknown" : remote;
    }

    @POST
    @Path("/register")
    public Response register(@Valid @NotNull RegisterRequest request,
                             @HeaderParam("X-Forwarded-For") String forwarded) {
        char[] password = request.getPassword().toCharArray();
        try {
            AccountService.Login login = accounts.register(
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
                    request.getEmail(),
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
                                "That email and password combination was not recognised."))
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
