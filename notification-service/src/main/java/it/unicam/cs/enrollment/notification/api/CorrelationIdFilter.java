package it.unicam.cs.enrollment.notification.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Reads the correlation id the caller sent, and puts it in this service MDC.
 *
 * <p>THIS IS THE HALF THAT MAKES DISTRIBUTED TRACING POSSIBLE AT ALL, and it is
 * the piece people leave out. The enrollment service generates an id per
 * request and sends it on a header; this service adopts it instead of
 * generating its own. One id therefore appears in TWO log files, and grepping
 * it shows the whole journey across the boundary.
 *
 * <p>Leave this out and each service invents its own id. Both logs are
 * individually tidy and cannot be joined, which is the state most two-service
 * systems are actually in.
 *
 * <p>WHERE THIS GOES NEXT. Propagating one header by hand is fine for two
 * services and does not scale to twenty - you also want a span per hop, timing,
 * and a parent/child tree. That is W3C Trace Context (the {@code traceparent}
 * header) and OpenTelemetry, which Spring exposes as Micrometer Tracing. The
 * concept is identical to this file; the machinery does the plumbing and adds
 * the timing. Knowing that this is the hand-rolled version of a standard is
 * worth more than having configured the standard once.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String incoming = request.getHeader(HEADER);

        // ADOPT the caller id when there is one. A fresh id is generated only
        // when this service is called directly - by a test, or by curl.
        String correlationId = (incoming == null || incoming.isBlank())
                ? "n-" + UUID.randomUUID().toString().substring(0, 6)
                : sanitise(incoming);

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Pooled thread: leaving the id behind leaks it into the next
            // request handled on this thread.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * The header is attacker-controlled and goes straight into a log file. A
     * newline in it lets somebody forge whole log lines. Same rule, and the same
     * fix, as in the enrollment service - and it has to be applied HERE too,
     * because this service cannot know that the caller already sanitised it.
     */
    private String sanitise(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
