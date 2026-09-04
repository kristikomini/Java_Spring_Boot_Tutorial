package it.unicam.cs.enrollment.fieldbook.security;

import it.unicam.cs.enrollment.api.dto.response.ProblemDetail;
import jakarta.annotation.Priority;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Arrays;
import java.util.List;

/**
 * Rejects state-changing requests that did not come from this application.
 *
 * <h2>The attack, in one paragraph</h2>
 * You are signed in to the fieldbook. You open a different site in another tab.
 * That site contains a form that posts to the fieldbook and submits itself with
 * JavaScript. Your browser attaches your session cookie, because cookies are
 * attached by destination and not by who asked - so the request arrives fully
 * authenticated, and the attacker never saw the cookie. That is cross-site
 * request forgery, and it is a consequence of cookies working the way they do
 * rather than of any bug you wrote.
 *
 * <h2>The two defences, and why both are here</h2>
 * <ol>
 *   <li>{@code SameSite=Strict} on the cookie - see {@link SessionCookies}. The
 *       browser simply does not attach the cookie to a cross-site request.
 *       Excellent, and entirely dependent on the browser being one that
 *       implements it.</li>
 *   <li>This filter: a custom header the request must carry. A cross-site
 *       {@code <form>} cannot set headers at all - that is a hard limit of
 *       HTML, not a policy - and a cross-origin {@code fetch} that adds one
 *       turns the request into a PREFLIGHTED request, which the browser will
 *       only send after an {@code OPTIONS} call that this application never
 *       answers with permission.</li>
 * </ol>
 *
 * <p>The value of the header is irrelevant and is never checked. Its PRESENCE
 * is the signal, because presence is the thing an attacker cannot arrange. If
 * that feels too easy, that is the right reaction and the reason to know the
 * alternative: a synchroniser token, a random value put in the page and echoed
 * back, which is what you use when the API must also serve genuine cross-origin
 * callers and CORS is therefore relaxed. This application is same-origin only,
 * so the cheap defence is the complete one.
 *
 * <h2>Why GET is exempt</h2>
 * Because a GET must not change anything, so forging one gains an attacker
 * nothing. That is a rule about your own design as much as about this filter:
 * the moment a GET has a side effect, this exemption becomes a hole, and the
 * bug to fix is the GET.
 */
@Provider
@CsrfProtected
@Priority(Priorities.AUTHENTICATION - 10)
public class CsrfFilter implements ContainerRequestFilter {

    private static final List<String> MUTATING =
            Arrays.asList(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!MUTATING.contains(ctx.getMethod())) {
            return;
        }
        if (ctx.getHeaderString(SessionCookies.CSRF_HEADER) != null) {
            return;
        }
        ProblemDetail body = new ProblemDetail();
        body.setType("about:blank");
        body.setTitle("Missing request header");
        body.setStatus(Response.Status.FORBIDDEN.getStatusCode());
        body.setErrorCode("CSRF_HEADER_MISSING");
        body.setDetail("State-changing requests must carry the " + SessionCookies.CSRF_HEADER
                + " header. This is a cross-site request forgery defence.");
        body.setInstance(ctx.getUriInfo().getPath());
        ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build());
    }
}
