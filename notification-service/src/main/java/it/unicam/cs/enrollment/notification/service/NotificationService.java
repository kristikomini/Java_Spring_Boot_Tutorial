package it.unicam.cs.enrollment.notification.service;

import it.unicam.cs.enrollment.notification.domain.EnrollmentCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ============================================================================
 * IDEMPOTENCY - THE THING THE NETWORK MAKES NECESSARY
 * ============================================================================
 * Fieldbook chapter 33 says, of message queues: "delivery is at least once, so
 * consumers must be idempotent". This class is that sentence as code, and it is
 * worth being precise about WHY the requirement appeared, because it did not
 * exist five minutes ago.
 *
 * <p>When this logic was a CDI observer inside the enrollment application, it
 * ran exactly once, in the same JVM, in the same call stack. There was no
 * "delivered twice" to worry about.
 *
 * <p>Now the caller sends an HTTP request. Consider what happens when the
 * response is lost - the work completed, the acknowledgement did not:
 *
 * <pre>
 *   caller                          this service
 *   ------                          ------------
 *   POST /api/notifications  ---->  received, email sent
 *                            &lt;--X   (response times out)
 *   retry                    ---->  received AGAIN
 * </pre>
 *
 * <p>The caller CANNOT distinguish "the request never arrived" from "the
 * response was lost". That is not a bug in anybody code, it is a property of
 * networks, and it is the reason retries and idempotency are always mentioned
 * together. A retry without an idempotent consumer sends two emails; an
 * idempotent consumer without retries loses notifications. You need both.
 *
 * <p>THE MECHANISM: the CALLER supplies a stable {@code eventId}, and this
 * service refuses to process the same one twice. The id must be generated once,
 * by the sender, and reused on every retry - generating a fresh id per attempt
 * defeats the whole thing, and is the most common way this is implemented
 * wrongly.
 *
 * <p>THE LIMIT, stated rather than hidden: this store is in memory. Restart the
 * service and it forgets, so a redelivery across a restart sends a duplicate.
 * Acceptable when the worst case is one extra email; not acceptable for
 * anything that charges money. The fix is a table with a unique constraint on
 * the event id - which is exactly the mechanism the enrollments table already
 * uses to stop a student being enrolled twice. Same problem, same answer, one
 * layer out.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Keep the last N event ids. Bounded, because an unbounded set is a leak. */
    private static final int MAX_REMEMBERED = 10_000;

    /**
     * A bounded LRU set, built from LinkedHashMap with access ordering.
     *
     * <p>synchronizedMap because this is a singleton and every request thread
     * touches it - chapter 06 rule about one instance and many threads, met in
     * the smallest possible service. A plain HashMap here would corrupt under
     * concurrent writes, and the symptom would be an infinite loop rather than
     * an exception.
     *
     * <p>{@code removeEldestEntries} is the hook that makes the bound work: it
     * is consulted after every insert, and returning true evicts the oldest
     * entry. This is the JDK giving you an LRU cache in three lines, and it is a
     * genuinely good interview answer to "how would you write a bounded cache
     * without a library".
     */
    private final Map<String, Long> processed = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_REMEMBERED;
                }
            });

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();

    /**
     * @return true if this event was newly processed, false if it was a duplicate
     */
    public boolean handle(EnrollmentCreatedEvent event) {
        // putIfAbsent is ATOMIC on a synchronized map: check-then-act as one
        // operation. Writing `if (!containsKey) put(...)` instead would be a
        // race - two threads could both pass the check and both process the
        // event, which is the exact bug this class exists to prevent.
        Long seenAt = processed.putIfAbsent(event.eventId(), System.currentTimeMillis());

        if (seenAt != null) {
            duplicates.incrementAndGet();
            log.info("Duplicate event {} for enrollment {} - ignored",
                    event.eventId(), event.enrollmentId());
            return false;
        }

        accepted.incrementAndGet();

        // The "work". A real service would hand this to the mail outbox that
        // already exists in the Jakarta EE application - which is itself worth
        // noticing: the outbox pattern solves the same reliability problem one
        // layer further in.
        log.info("Notifying {} <{}>: enrolled in {} ({})",
                event.studentNumber(), event.studentEmail(),
                event.courseCode(), event.courseTitle());

        return true;
    }

    public long acceptedCount() {
        return accepted.get();
    }

    public long duplicateCount() {
        return duplicates.get();
    }
}
