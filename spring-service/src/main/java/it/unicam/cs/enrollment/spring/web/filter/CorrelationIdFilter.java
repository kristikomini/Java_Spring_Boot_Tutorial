package it.unicam.cs.enrollment.spring.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request an id, puts it in the logging context, and returns it in a
 * header.
 *
 * <p>The Spring translation of
 * it.unicam.cs.enrollment.api.filter.CorrelationIdFilter:
 *
 * <pre>
 *   JAX-RS                                 Spring
 *   ------------------------------------   ------------------------------------
 *   {@literal @}Provider {@literal @}PreMatching                 {@literal @}Component + {@literal @}Order
 *   ContainerRequestFilter                 OncePerRequestFilter (servlet level)
 *   ContainerResponseFilter                the same class, after chain.doFilter
 *   {@literal @}Priority(1)                            {@literal @}Order(HIGHEST_PRECEDENCE)
 * </pre>
 *
 * <p>ONE REAL DIFFERENCE: a servlet filter sits BELOW Spring MVC, so it wraps
 * every request the container handles - including static resources, the actuator
 * endpoints, and requests that never match a controller. The JAX-RS filter only
 * sees requests that reach the JAX-RS application. That is usually what you want
 * here (a 404 for an unmapped path should still be correlated) and is
 * occasionally surprising.
 *
 * <p>WHY OncePerRequestFilter RATHER THAN Filter. A plain filter can run twice
 * for one request - on a forward, an include, or an async dispatch - which would
 * generate a second id and overwrite the first mid-request. The base class keeps
 * a request attribute to make sure the body runs once. Extending Filter directly
 * is a bug that only shows up once error dispatching or async is involved.
 *
 * <p>MDC IS A ThreadLocal, AND THAT IS THE WHOLE CATCH. Anything logged on this
 * thread picks the id up automatically, with no parameter passing. But a thread
 * from a pool is reused for the next request, so failing to clear it leaks one
 * user id into another user log lines. Hence the {@code finally}. The same trap
 * exists in the Jakarta EE version and in every MDC-using codebase; a request
 * handed to an {@code @Async} method or a parallel stream does NOT inherit the
 * MDC, which is the follow-up question worth being ready for.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString().substring(0, 8);
        } else {
            correlationId = sanitise(correlationId);
        }

        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);

        // Set on the response BEFORE the chain runs. Once the controller has
        // written a byte of the body the headers are committed and any later
        // attempt is silently ignored - which is exactly what happens if you try
        // to add this after chain.doFilter, and it fails with no error at all.
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        long startNanos = System.nanoTime();
        try {
            log.info("--> {} {}", request.getMethod(), request.getRequestURI());
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            int status = response.getStatus();
            if (status >= 500) {
                log.warn("<-- {} {} {} ({}ms)",
                        request.getMethod(), request.getRequestURI(), status, elapsedMillis);
            } else {
                log.info("<-- {} {} {} ({}ms)",
                        request.getMethod(), request.getRequestURI(), status, elapsedMillis);
            }
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    /**
     * The incoming header is attacker-controlled and goes straight into the log.
     *
     * <p>Without this, a newline in the header lets someone forge entire log
     * lines - LOG INJECTION, and it is how an intruder makes their own activity
     * look like a routine health check. Stripping to a safe alphabet and capping
     * the length is the whole fix. Fieldbook chapter 15 has the general rule:
     * anything from the network is input, including the parts that do not look
     * like input.
     */
    private String sanitise(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
