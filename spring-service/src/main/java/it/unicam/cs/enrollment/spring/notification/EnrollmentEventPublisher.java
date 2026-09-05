package it.unicam.cs.enrollment.spring.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ============================================================================
 * THE PHASE THAT MATTERS
 * ============================================================================
 * Fieldbook chapter 10 has a section called "Events, and the phase that
 * matters". This is that section, in Spring, and it is the single most
 * important line in this package:
 *
 *     phase = TransactionPhase.AFTER_COMMIT
 *
 * WITHOUT IT, the listener runs inside the enrollment transaction. Consider
 * what that means:
 *
 *   1. The transaction can still ROLL BACK after the notification was sent -
 *      so the student is emailed about an enrollment that does not exist. That
 *      is not a hypothetical: any constraint violation at flush time does it.
 *
 *   2. The HTTP call, its timeouts and its retries all happen while the
 *      DATABASE TRANSACTION IS OPEN - so a pessimistic lock on the course row
 *      is held for as long as the notification service takes to answer. A slow
 *      mail service now blocks other students from enrolling in that course.
 *      That one is genuinely alarming, and it is invisible until the downstream
 *      service has a bad day.
 *
 * AFTER_COMMIT fires only once the transaction has committed, so the fact is
 * durable before anybody is told about it and no lock is held while the network
 * call happens. This is the Spring equivalent of the CDI
 * {@code @Observes(during = AFTER_SUCCESS)} the Jakarta EE application uses.
 *
 * THE TRADE-OFF THIS CREATES, and it is worth being able to state: there is now
 * a window where the enrollment is committed and the notification has not been
 * sent. If the process dies in that window, the event is lost forever. That is
 * the DUAL WRITE PROBLEM, and the standard answer is the transactional outbox -
 * write the event to a table in the same transaction, and let a poller deliver
 * it. The Jakarta EE application in this repository already does exactly that
 * for mail. Choosing the simpler version here is a decision, not an oversight,
 * and it is defensible only because losing a notification is survivable.
 */
@Component
public class EnrollmentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentEventPublisher.class);

    private final NotificationClient notificationClient;

    public EnrollmentEventPublisher(NotificationClient notificationClient) {
        this.notificationClient = notificationClient;
    }

    /**
     * {@code @Async} as well, so the HTTP call does not sit in the request
     * thread. The user gets their 201 without waiting for the notification
     * service - which is the entire justification for this boundary.
     *
     * <p>THE MDC DOES NOT CROSS A THREAD BOUNDARY BY ITSELF. MDC is a
     * ThreadLocal, so an {@code @Async} method starts with an EMPTY context and
     * the correlation id is lost exactly where you most want it. AsyncConfig
     * fixes that with a TaskDecorator.
     *
     * <p>THE EXECUTOR IS NAMED EXPLICITLY, AND IT HAS TO BE. This was written
     * first as a bare {@code @Async}, and it silently did the wrong thing.
     *
     * <p>{@code @EnableScheduling} contributes a {@code taskScheduler} bean and
     * AsyncConfig contributes {@code notificationExecutor}. Neither is called
     * {@code taskExecutor}, so Spring could not choose, logged
     *
     * <pre>
     *   More than one TaskExecutor bean found within the context, and none is
     *   named 'taskExecutor'
     * </pre>
     *
     * and fell back to a default executor - one WITHOUT the TaskDecorator. The
     * notification was still delivered, so nothing looked broken; but the
     * correlation id was lost, and the receiving service logged a locally
     * generated id instead. The two logs could no longer be joined, which was
     * the entire purpose of the exercise.
     *
     * <p>It is a good example of the failure mode worth watching for in Spring:
     * not an exception, but a WARNING in the log and a silently degraded
     * behaviour. Naming the executor removes the ambiguity, and naming it is
     * good practice anyway - a project with several {@code @Async} concerns
     * should not have them all sharing one anonymous pool, because a slow
     * notification then delays an unrelated background task.
     */
    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEnrollmentCreated(EnrollmentCreatedEvent event) {
        log.debug("Publishing enrollment {} to the notification service", event.enrollmentId());
        notificationClient.notifyEnrollment(event);
    }
}
