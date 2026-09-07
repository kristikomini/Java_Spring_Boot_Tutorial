package it.unicam.cs.enrollment.fieldbook.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unicam.cs.enrollment.fieldbook.domain.AuthSession;
import it.unicam.cs.enrollment.fieldbook.service.AccountService;
import it.unicam.cs.enrollment.web.dto.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;

/**
 * Turns the session cookie into an identity, and rejects the request if it
 * cannot.
 *
 * <h2>Why an interceptor and not a check at the top of every method</h2>
 * Because "every method" is a promise nobody keeps. A cross-cutting rule
 * enforced centrally applies to endpoints written after the rule was written,
 * which is the only kind of enforcement worth having. This is the same idea as
 * {@code @Transactional} and {@code @Loggable} elsewhere in this codebase:
 * behaviour wrapped around a method rather than pasted into it.
 *
 * <h2>Interceptor, filter, or aspect - Spring gives you three, and they differ</h2>
 * <ul>
 *   <li>A servlet {@code Filter} sits outside Spring MVC entirely. It sees
 *       every request including static files, and it has NO idea which handler
 *       is about to run. Right for correlation ids - see
 *       {@code web.filter.CorrelationIdFilter} - and wrong for this, because it
 *       cannot read an annotation on a method it cannot see.</li>
 *   <li>A {@code HandlerInterceptor} runs inside the {@code DispatcherServlet}
 *       and is handed the {@link HandlerMethod}. That is exactly what
 *       {@link Authenticated} needs.</li>
 *   <li>An {@code @Aspect} wraps the method call itself, after arguments are
 *       bound. Too late here: binding and validating a request body for a
 *       caller you have not identified is work done on behalf of a stranger.</li>
 * </ul>
 *
 * <h2>Ordering</h2>
 * {@link CsrfInterceptor} is registered BEFORE this one in {@code WebConfig},
 * so a forged request is refused without a database lookup. The general rule is
 * that the cheapest rejection goes first.
 *
 * <h2>Why the collaborators arrive as {@link ObjectProvider}</h2>
 * An interceptor is a REQUEST-time object: outside a request it has nothing to
 * do and needs neither the account service nor the current user. Declaring them
 * as providers says exactly that - resolve them when a request arrives, not
 * when the context starts.
 *
 * <p>It also has a practical effect worth knowing about, because it is the kind
 * of thing that decides how pleasant a codebase is to test. {@code @WebMvcTest}
 * loads every {@code WebMvcConfigurer} and every {@code HandlerInterceptor} it
 * can find - that is how a web slice can test routing and filters at all - but
 * it does NOT load plain {@code @Service} beans. With direct constructor
 * injection, a slice test for an unrelated controller would fail to start with
 * "no qualifying bean of type AccountService", and the only cure would be a
 * mock of a class that test has never heard of. With providers, the slice
 * starts, and the dependency is only demanded by a request that actually hits
 * an {@code @Authenticated} endpoint.
 *
 * <p>{@code ObjectProvider} is Spring's answer to "this dependency is optional,
 * or late, or plural". {@code getObject()} is the strict form used below - a
 * request that needs an identity must not proceed without one - while
 * {@code getIfAvailable()} and {@code ifAvailable()} are the tolerant forms.
 *
 * <h2>Returning {@code false}</h2>
 * {@code preHandle} returning {@code false} stops the chain: the handler method
 * never runs, and this interceptor is responsible for having written the whole
 * response. Returning {@code true} after writing an error is the bug to know
 * about - the handler runs anyway and the response is two bodies glued
 * together.
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    private final ObjectProvider<AccountService> accounts;
    private final ObjectProvider<CurrentUser> currentUser;
    private final ObjectMapper json;

    public AuthenticationInterceptor(ObjectProvider<AccountService> accounts,
                                     ObjectProvider<CurrentUser> currentUser,
                                     ObjectMapper json) {
        this.accounts = accounts;
        this.currentUser = currentUser;
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        if (!AnnotatedHandlers.has(handler, Authenticated.class)) {
            return true;
        }

        String rawToken = SessionCookies.read(request);
        Optional<AuthSession> session = accounts.getObject().resolve(rawToken);

        if (session.isEmpty()) {
            writeProblem(request, response,
                    "Not signed in",
                    "This endpoint needs a valid session. Sign in at /api/fieldbook/auth/login.");
            return false;
        }

        currentUser.getObject().set(session.get().getAccount(), session.get());
        return true;
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
    private void writeProblem(HttpServletRequest request, HttpServletResponse response,
                              String title, String detail) throws IOException {
        ProblemDetail body = new ProblemDetail();
        body.setType("about:blank");
        body.setTitle(title);
        body.setStatus(HttpStatus.UNAUTHORIZED.value());
        body.setDetail(detail);
        body.setInstance(request.getRequestURI());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }
}
