package it.unicam.cs.enrollment.spring.notification;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import it.unicam.cs.enrollment.spring.web.filter.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * ============================================================================
 * A METHOD CALL BECOMES A REQUEST THAT CAN TIME OUT
 * ============================================================================
 * Fieldbook chapter 33 offers that sentence as the honest answer to give in an
 * interview. This class is the sentence made concrete, and it is worth counting
 * what had to be added to replace ONE LINE of CDI:
 *
 * <pre>
 *   Before (same JVM):
 *       enrollmentCreatedEvent.fire(new EnrollmentCreatedEvent(...));
 *
 *   After (across a network):
 *       a client              because there is a URL now
 *       a connect timeout     because the host may not answer
 *       a read timeout        because it may answer slowly, forever
 *       a retry               because a lost packet is not a failure
 *       a backoff             because retrying instantly makes it worse
 *       a circuit breaker     because retrying a dead service is pointless
 *       a fallback            because the enrollment must still succeed
 *       an idempotency key    because a retry can arrive twice
 *       header propagation    because the two logs must be joinable
 * </pre>
 *
 * <p>Nine things. None of them is hard; all of them are required; and forgetting
 * any one produces a failure that only appears under load or during an incident.
 * THAT is what "distributed is not free" means, and being able to list them is a
 * far better answer than naming tools.
 *
 * <p>THE ORDER OF THE ANNOTATIONS MATTERS AND IS COUNTER-INTUITIVE. Resilience4j
 * applies its aspects in a fixed order:
 *
 * <pre>
 *   Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( call )))))
 * </pre>
 *
 * <p>Retry is OUTERMOST, so it wraps the circuit breaker. That is the right way
 * round: each retry attempt is recorded by the breaker, so a run of failures
 * opens it and subsequent retries are rejected instantly instead of waiting for
 * timeouts. If the breaker were outside the retry, one logical call would count
 * as one failure however many attempts it made, and the breaker would take far
 * too long to notice a dead dependency.
 */
@Component
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    /** Named in application.yml, where the retry and breaker are configured. */
    public static final String INSTANCE = "notifications";

    private final RestClient restClient;

    public NotificationClient(RestClient.Builder builder,
                              @Value("${enrollment.notifications.base-url}") String baseUrl,
                              @Value("${enrollment.notifications.connect-timeout-ms:1000}")
                              int connectTimeoutMs,
                              @Value("${enrollment.notifications.read-timeout-ms:2000}")
                              int readTimeoutMs) {

        // ===================================================================
        // TIMEOUTS. THE MOST IMPORTANT FOUR LINES IN THIS FILE.
        // ===================================================================
        // The default in most HTTP clients is NO TIMEOUT AT ALL - wait forever.
        // That is the single most damaging default in distributed systems,
        // because a downstream service that hangs rather than failing will hold
        // every one of your request threads until your service stops answering
        // too. The failure spreads upstream, and the service that actually broke
        // is not the one that pages.
        //
        // TWO timeouts, and they answer different questions:
        //   connect - could I open a socket? (host down, DNS wrong, firewall)
        //   read    - did bytes arrive in time? (service alive but wedged)
        // A connect timeout alone leaves you exposed to the second and worse case.
        //
        // The numbers are a JUDGEMENT, not a default. Read timeout must exceed
        // the p99 of the downstream call, or you will time out on requests that
        // were about to succeed - turning a slow dependency into a failed one and
        // adding retry load to a service that is already struggling.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                // ============================================================
                // CORRELATION ID PROPAGATION
                // ============================================================
                // An interceptor, so no call site can forget it. Reading the id
                // out of the MDC means it is the SAME id CorrelationIdFilter put
                // there for the inbound request - so one grep spans both
                // services.
                //
                // Chapter 33 notes that CorrelationIdFilter already exists and
                // that making it cross a network "writes the chapter itself".
                // This is that crossing, and it is four lines.
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
                    if (correlationId != null) {
                        request.getHeaders().add(
                                CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    /**
     * Send the event, and do not let a notification failure lose an enrollment.
     *
     * <p>{@code @Retry} handles the transient case: a dropped packet, a pod
     * being restarted, a momentary GC pause. Configured with EXPONENTIAL BACKOFF
     * in application.yml, which matters more than it looks - retrying
     * immediately, three times, from every one of your instances at once, is a
     * denial-of-service attack on a service that was merely slow. It has a name:
     * a retry storm, and it is how a small incident becomes a large one.
     *
     * <p>{@code @CircuitBreaker} handles the sustained case. Once enough calls
     * fail it OPENS and subsequent calls fail instantly without touching the
     * network - which protects your threads AND gives the downstream service
     * room to recover instead of being hammered while it restarts. After a wait
     * it goes HALF-OPEN and lets a few through to test the water.
     *
     * <p>THE FALLBACK IS THE PART THAT MATTERS MOST HERE, and it encodes a
     * business decision rather than a technical one. When notification fails, we
     * LOG AND CARRY ON. We do not fail the enrollment.
     *
     * <p>That is only correct because of the property chapter 33 identified when
     * it called this "a good cut": nobody is waiting for the notification, and it
     * never participated in the enrollment transaction. A student who got a seat
     * but no email has a minor problem; a student who lost a seat because the
     * mail service was down has a serious one.
     *
     * <p>Get that judgement backwards - make the enrollment depend on the
     * notification - and you have built a distributed system whose availability
     * is the PRODUCT of its parts. Two services at 99.9% chained together are
     * 99.8%; ten are 99%. The arithmetic is why "which failures may propagate"
     * is the first question to ask about any boundary.
     */
    // THE FALLBACK GOES ON THE OUTERMOST ANNOTATION ONLY, and this cost a
    // debugging session worth writing down.
    //
    // The first version had fallbackMethod on BOTH @Retry and @CircuitBreaker.
    // It looked more defensive and it silently disabled the retry entirely.
    //
    // Remember the nesting: Retry ( CircuitBreaker ( call ) ). With a fallback
    // on the INNER annotation, the circuit breaker catches the exception, runs
    // the fallback, and returns NORMALLY. Retry therefore sees a success and
    // never retries. The metric said so in as many words:
    //
    //     resilience4j_retry_calls_total{kind="successful_without_retry"} 3.0
    //
    // Three calls, three "successes", zero retries - while the downstream
    // service was refusing every connection. Nothing failed, nothing warned,
    // and the retry policy was decoration.
    //
    // With the fallback only on @Retry, the exception propagates out of the
    // breaker (which records the failure, correctly), Retry sees it and retries
    // with backoff, and the fallback runs only once every attempt is exhausted.
    //
    // THE GENERAL RULE: a fallback terminates the chain wherever you put it.
    // Put it at the outermost layer, or the layers outside it never see a
    // failure to act on. This is the kind of thing that is invisible in review
    // and obvious in a metric, which is the argument for having the metric.
    @Retry(name = INSTANCE, fallbackMethod = "notificationFailed")
    @CircuitBreaker(name = INSTANCE)
    public void notifyEnrollment(EnrollmentCreatedEvent event) {
        restClient.post()
                .uri("/api/notifications")
                .body(event)
                .retrieve()
                .toBodilessEntity();

        log.debug("Notification sent for enrollment {} (event {})",
                event.enrollmentId(), event.eventId());
    }

    /**
     * The fallback.
     *
     * <p>Its signature must be the original parameters PLUS a Throwable, or
     * Resilience4j cannot bind it - and the failure mode is a runtime error
     * complaining about the fallback rather than about your service, which is
     * confusing the first time.
     *
     * <p>Logged at WARN, not ERROR. The enrollment succeeded; a notification was
     * lost. That is degraded, not broken, and the distinction is what keeps an
     * error log worth reading.
     *
     * <p>WHAT A PRODUCTION VERSION WOULD DO INSTEAD, and it is worth knowing the
     * ladder: write the event to a local outbox table in the SAME transaction as
     * the enrollment, and let a separate poller deliver it with retries. That is
     * the TRANSACTIONAL OUTBOX pattern, it survives a restart, and it is the only
     * way to be sure an event is never silently dropped.
     *
     * <p>The Jakarta EE application in this repository already implements exactly
     * that for mail - see {@code mail/domain/OutboxMessage.java}. So the honest
     * summary is that the older application has the more robust design here, and
     * this one is a deliberately simpler illustration of the client-side
     * patterns.
     */
    @SuppressWarnings("unused")
    private void notificationFailed(EnrollmentCreatedEvent event, Throwable cause) {
        log.warn("Notification for enrollment {} (event {}) could not be delivered: {} - "
                        + "the enrollment itself is unaffected",
                event.enrollmentId(), event.eventId(), cause.toString());
    }

    /**
     * Boot applies this to every RestClient.Builder it creates.
     *
     * <p>Present as a reminder that the timeouts above are set on ONE client. A
     * project with five clients and timeouts on four of them has a hole, and the
     * fifth is the one that will hang. Setting a floor centrally, and overriding
     * per client where a call is genuinely slower, is the arrangement that scales.
     */
    @Component
    static class DefaultTimeouts implements RestClientCustomizer {
        @Override
        public void customize(RestClient.Builder builder) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(2));
            factory.setReadTimeout(Duration.ofSeconds(5));
            builder.requestFactory(factory);
        }
    }
}
