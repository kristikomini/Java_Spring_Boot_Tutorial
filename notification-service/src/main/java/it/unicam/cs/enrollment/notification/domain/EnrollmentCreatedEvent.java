package it.unicam.cs.enrollment.notification.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * The event, as it crosses the wire.
 *
 * <p>THIS RECORD IS DUPLICATED. An identical shape exists in spring-service, and
 * the duplication is a deliberate architectural choice rather than an oversight.
 *
 * <p>THE ALTERNATIVE is a shared "contracts" module both services depend on. It
 * removes the copy and it couples the two services at BUILD time: they must now
 * upgrade together, and the shared module becomes a place where a change by one
 * team breaks the other team build. That coupling is precisely what a service
 * boundary was supposed to remove, so a shared DTO jar is the most common way a
 * microservices migration quietly recreates the monolith.
 *
 * <p>THE COST of duplicating is real too: the two copies can drift, and nothing
 * catches it at compile time. The industry answer is CONSUMER-DRIVEN CONTRACT
 * TESTING (Pact, Spring Cloud Contract) - the consumer publishes what it
 * expects, and the producer build fails if it stops providing it. That is the
 * right answer at scale and is more machinery than two services justify.
 *
 * <p>Being able to lay out those three options - shared jar, duplicate, contract
 * tests - and say what each costs is a genuinely senior-sounding answer to a
 * question juniors are often asked.
 *
 * <p>WHY THE FIELDS ARE FLAT AND PRIMITIVE. No entity, no object graph, no lazy
 * anything. An event is a fact that already happened, serialised at the moment
 * it happened. It must be readable by a consumer that has no access to the
 * sender database - which is the whole point.
 */
public record EnrollmentCreatedEvent(

        /**
         * THE IDEMPOTENCY KEY, and the most important field here.
         *
         * <p>Generated ONCE by the sender and reused on every retry. Generate a
         * fresh one per attempt and deduplication cannot work - the receiver has
         * no other way to tell a retry from a new event.
         */
        @NotBlank String eventId,

        @NotNull Long enrollmentId,
        @NotBlank String studentNumber,
        @NotBlank String studentEmail,
        @NotBlank String courseCode,
        @NotBlank String courseTitle,

        /**
         * When the enrollment happened - NOT when this message was sent.
         *
         * <p>They differ by however long the retries took, and a consumer that
         * uses the arrival time instead will eventually report an enrollment as
         * happening at 03:00 because that is when the queue drained.
         */
        @NotNull Instant occurredAt) {
}
