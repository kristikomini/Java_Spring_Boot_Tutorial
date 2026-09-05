package it.unicam.cs.enrollment.spring.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * The event this service publishes when an enrollment is created.
 *
 * <p>An identical record exists in notification-service. See the long comment
 * there for why the duplication is deliberate rather than sloppy, and what the
 * three alternatives cost.
 *
 * <p>THE eventId IS GENERATED HERE, ONCE, at the moment the enrollment happens -
 * and that placement is the whole of the idempotency contract. Every retry of
 * the same event carries the same id, so the receiver can recognise it. Generate
 * it in the HTTP client instead and each attempt gets a fresh id, the receiver
 * sees three distinct events, and three emails go out - which is the most common
 * way idempotency is implemented wrongly.
 */
public record EnrollmentCreatedEvent(
        String eventId,
        Long enrollmentId,
        String studentNumber,
        String studentEmail,
        String courseCode,
        String courseTitle,
        Instant occurredAt) {

    public static EnrollmentCreatedEvent of(Long enrollmentId, String studentNumber,
                                            String studentEmail, String courseCode,
                                            String courseTitle, Instant occurredAt) {
        return new EnrollmentCreatedEvent(UUID.randomUUID().toString(), enrollmentId,
                studentNumber, studentEmail, courseCode, courseTitle, occurredAt);
    }
}
