package it.unicam.cs.enrollment.service;

import it.unicam.cs.enrollment.domain.event.EnrollmentCreatedEvent;
import it.unicam.cs.enrollment.domain.event.GradeRecordedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reacts to domain events by logging them, so the mechanism can be watched.
 *
 * <h2>Read this alongside {@code EnrollmentMailListener}</h2>
 * Confirmation email is no longer hypothetical: the {@code mail} package
 * observes these same two events and queues a real message. This class stays
 * because it is the SIMPLE version of the same idea, and because the contrast
 * between the two is the lesson.
 *
 * <p>Both classes observe {@code EnrollmentCreatedEvent}. Neither knows the
 * other exists. {@code EnrollmentService} knows about neither. Adding the
 * entire mailing system required no change to the code that enrolls a student -
 * which is the claim the "observers are pluggable" paragraph below makes, now
 * demonstrated rather than asserted.
 *
 * <p>Note also that the two use DIFFERENT transaction phases, for a reason
 * spelled out in {@code EnrollmentMailListener}: this one has a genuine
 * external side effect (a log line cannot be un-written) and defers to
 * {@code AFTER_SUCCESS}; the mail listener only writes a database row, so it
 * runs inside the transaction on purpose. The phase follows the work, not the
 * habit.
 *
 * <h2>The point of this class</h2>
 * {@link EnrollmentService} does not import it, does not know it exists, and
 * would behave identically if it were deleted. That is the whole idea:
 * OBSERVERS ARE PLUGGABLE. Adding "also notify the professor" means adding a
 * class, not editing the enrollment logic and re-testing it.
 *
 * <h2>{@code TransactionPhase} - the detail that actually matters</h2>
 * By default an observer runs SYNCHRONOUSLY, inline, inside the firing
 * transaction. For a side effect that reaches outside the database - an email,
 * an HTTP call, a message on a queue - that default is a bug waiting to happen:
 * <ul>
 *   <li>if the transaction later ROLLS BACK, you have already sent an email
 *       about an enrollment that does not exist;</li>
 *   <li>if the email server is slow, you are holding a database transaction
 *       (and its locks) open while waiting on the network.</li>
 * </ul>
 *
 * <p>{@code @Observes(during = TransactionPhase.AFTER_SUCCESS)} defers the
 * observer until the transaction has COMMITTED. The phases:
 * <ul>
 *   <li>{@code IN_PROGRESS} (default) - immediately, inside the transaction.
 *       Correct for work that must be atomic with the change itself.</li>
 *   <li>{@code AFTER_SUCCESS} - only if the commit succeeded. The right choice
 *       for external side effects.</li>
 *   <li>{@code AFTER_FAILURE} - only on rollback. Useful for alerting.</li>
 *   <li>{@code AFTER_COMPLETION} - either way.</li>
 *   <li>{@code BEFORE_COMPLETION} - during commit, before it finishes.</li>
 * </ul>
 */
@ApplicationScoped
public class EnrollmentNotificationListener {

    private static final Logger LOG = LoggerFactory.getLogger(EnrollmentNotificationListener.class);

    /**
     * Sends the enrollment confirmation - but only once the enrollment is
     * genuinely committed.
     *
     * <p>The parameter annotated {@code @Observes} is what makes this an
     * observer method; its TYPE is what selects which events arrive. No
     * registration, no listener interface, no configuration.
     */
    public void onEnrollmentCreated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) EnrollmentCreatedEvent event) {

        // Stand-in for a real mail/notification gateway.
        LOG.info("[NOTIFICATION] To {}: you are enrolled in {} - {} (enrolled at {})",
                event.getStudentEmail(),
                event.getCourseCode(),
                event.getCourseTitle(),
                event.getOccurredAt());
    }

    /**
     * A SECOND observer for the SAME event type.
     *
     * <p>CDI delivers the event to every matching observer. Ordering between
     * them is undefined unless you set {@code @Priority} - so never write
     * observers that depend on running in a particular order. If two things must
     * happen in sequence, that sequence is a single unit of work, not two
     * observers.
     *
     * <p>This one runs {@code IN_PROGRESS} (the default) because an audit record
     * SHOULD be atomic with the enrollment: if the enrollment rolls back, the
     * audit entry claiming it happened must roll back too.
     */
    public void auditEnrollmentCreated(@Observes EnrollmentCreatedEvent event) {
        LOG.info("[AUDIT] enrollment.created id={} student={} course={} at={}",
                event.getEnrollmentId(),
                event.getStudentNumber(),
                event.getCourseCode(),
                event.getOccurredAt());
    }

    /**
     * A different event type, therefore a different observer. Note the honours
     * case is singled out - the kind of small, human touch that makes an
     * application feel finished.
     */
    public void onGradeRecorded(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) GradeRecordedEvent event) {

        if (!event.isPassed()) {
            LOG.info("[NOTIFICATION] Student {} did not pass {} - the seat is kept for a retake",
                    event.getStudentNumber(), event.getCourseCode());
            return;
        }

        if (event.isWithHonours()) {
            LOG.info("[NOTIFICATION] Congratulations! Student {} passed {} with 30 e lode",
                    event.getStudentNumber(), event.getCourseCode());
        } else {
            LOG.info("[NOTIFICATION] Student {} passed {} with a grade of {}",
                    event.getStudentNumber(), event.getCourseCode(), event.getGrade());
        }
    }
}
