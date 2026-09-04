package it.unicam.cs.enrollment.fieldbook.security;

import it.unicam.cs.enrollment.api.dto.response.ProblemDetail;
import it.unicam.cs.enrollment.fieldbook.domain.AuthSession;
import it.unicam.cs.enrollment.fieldbook.service.AccountService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Optional;

/**
 * Turns the session cookie into an identity, and rejects the request if it
 * cannot.
 *
 * <h2>Why a filter and not a check at the top of every method</h2>
 * Because "every method" is a promise nobody keeps. A cross-cutting rule
 * enforced by a filter applies to endpoints written after the rule was written,
 * which is the only kind of enforcement worth having. This is the same
 * INTERCEPTOR idea as {@code @Transactional} and {@code @Loggable} elsewhere in
 * this codebase: behaviour wrapped around a method rather than pasted into it.
 *
 * <h2>{@code @Priority}, and why it is not decoration</h2>
 * Request filters run in ascending priority order.
 * {@link Priorities#AUTHENTICATION} is 1000, the lowest of the standard bands,
 * so this filter runs before authorisation, before parameter binding, before
 * anything that might do work on behalf of a caller who has not been
 * identified. Get this ordering wrong and you have an application that
 * carefully validates a request body for an anonymous stranger and only then
 * decides to refuse them.
 *
 * <h2>Cross-site request forgery is a separate filter</h2>
 * {@link CsrfFilter} handles that, bound by its own annotation, because login
 * and registration need CSRF protection while having no session to
 * authenticate. Two narrow filters that compose beat one that has to be both.
 *
 * <h2>Aborting</h2>
 * {@link ContainerRequestContext#abortWith} stops the chain: the resource
 * method never runs. Returning normally after setting something would not -
 * a filter that "returns 401" by falling off the end has done nothing at all,
 * and that is a real bug people write.
 */
@Provider
@Authenticated
@Priority(Priorities.AUTHENTICATION)
public class AuthenticationFilter implements ContainerRequestFilter {

    @Inject
    AccountService accounts;

    @Inject
    CurrentUser currentUser;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String rawToken = SessionCookies.read(ctx.getCookies());
        Optional<AuthSession> session = accounts.resolve(rawToken);

        if (!session.isPresent()) {
            ctx.abortWith(problem(ctx, Response.Status.UNAUTHORIZED,
                    "Not signed in",
                    "This endpoint needs a valid session. Sign in at /api/fieldbook/auth/login."));
            return;
        }

        currentUser.set(session.get().getAccount(), session.get());
    }

    /**
     * The same RFC 7807 problem shape the rest of the API uses.
     *
     * <p>Consistency here is not tidiness: a client that has one error parser
     * is a client that handles errors. An API whose auth failures look
     * different from its validation failures gets a caller who handles one and
     * crashes on the other.
     *
     * <p>Note what the body does NOT contain - no hint about whether the cookie
     * was absent, unknown or expired. All three are "sign in again" to an
     * honest caller, and free information to a dishonest one.
     */
    private static Response problem(ContainerRequestContext ctx, Response.Status status,
                                    String title, String detail) {
        ProblemDetail body = new ProblemDetail();
        body.setType("about:blank");
        body.setTitle(title);
        body.setStatus(status.getStatusCode());
        body.setDetail(detail);
        body.setInstance(ctx.getUriInfo().getPath());
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}
