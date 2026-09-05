package it.unicam.cs.enrollment.notification.api;

import it.unicam.cs.enrollment.notification.domain.EnrollmentCreatedEvent;
import it.unicam.cs.enrollment.notification.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The whole API of this service: one endpoint that accepts an event.
 *
 * <p>WHAT REPLACED WHAT. In the Jakarta EE application this is a CDI observer:
 *
 * <pre>
 *   void onEnrollment(&#64;Observes EnrollmentCreatedEvent event) { ... }
 * </pre>
 *
 * <p>A method annotation. Zero configuration, zero failure modes, and it cannot
 * be called from another machine. Here it is an HTTP endpoint with a port, a
 * URL, a serialisation format, a status code, validation, and a health check -
 * and the caller now needs a client, a timeout, a retry and a fallback.
 *
 * <p>That list IS the cost of the boundary. Chapter 33 says "a method call
 * becomes a request that can time out"; this is the same sentence measured in
 * files.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 202 ACCEPTED, not 200 and not 201.
     *
     * <p>202 means "I have taken responsibility for this, I have not necessarily
     * finished with it". That is exactly true here and it is the honest status
     * for asynchronous work: the caller must not wait for the email to be sent,
     * because the entire justification for this boundary was that nobody is
     * waiting.
     *
     * <p>A DUPLICATE ALSO RETURNS 202, and that is the subtle part. Returning
     * 409 for a duplicate would be wrong: from the caller point of view the
     * event HAS been accepted - it was accepted the first time - and a retry
     * that gets an error would be retried again forever. An idempotent endpoint
     * reports success for a repeat, and says in the body that nothing new
     * happened.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> accept(@Valid @RequestBody EnrollmentCreatedEvent event) {
        boolean isNew = notificationService.handle(event);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("eventId", event.eventId(), "processed", isNew));
    }

    /** Counters, so a test can assert that a retry did not send a second email. */
    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return Map.of(
                "accepted", notificationService.acceptedCount(),
                "duplicates", notificationService.duplicateCount());
    }
}
