package it.unicam.cs.enrollment.mail.service;

import it.unicam.cs.enrollment.domain.event.EnrollmentCreatedEvent;
import it.unicam.cs.enrollment.domain.event.GradeRecordedEvent;
import it.unicam.cs.enrollment.domain.model.Student;
import it.unicam.cs.enrollment.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the domain events into queued email.
 *
 * <h2>Where this sits</h2>
 * {@code EnrollmentService} fires {@code EnrollmentCreatedEvent} and knows
 * nothing about mail; this class knows about mail and nothing about enrolling.
 * Deleting it removes every email from the system and breaks no test of the
 * enrollment rules. That is the payoff application events were introduced for,
 * and it is worth checking that it is real: search {@code EnrollmentService}
 * for the word "mail" and you will not find it.
 *
 * <h2>{@code @EventListener}, not {@code @TransactionalEventListener} - and why
 * that is the opposite of the usual advice</h2>
 * Spring gives you two annotations for this, and choosing between them is the
 * whole lesson:
 * <ul>
 *   <li>{@code @EventListener} runs SYNCHRONOUSLY, inside the publisher's call
 *       and therefore inside its transaction. The publisher does not continue
 *       until the listener returns, and an exception here propagates back into
 *       the publisher and rolls the whole thing back.</li>
 *   <li>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} defers until
 *       the transaction has committed. The usual advice - and it is good advice
 *       - is that a listener with an EXTERNAL side effect must use it, so that
 *       a rolled-back transaction cannot send an email about an enrollment that
 *       does not exist.</li>
 * </ul>
 * {@code EnrollmentNotificationListener} follows that advice. This class
 * ignores it, deliberately.
 *
 * <p>The reason is that this listener has no external side effect. It writes a
 * ROW. Running inside the transaction is precisely what makes the outbox
 * pattern work:
 * <ul>
 *   <li>the enrollment commits and the queued email commits with it - one
 *       atomic act, so there is no state where the seat exists and the promise
 *       of a confirmation does not;</li>
 *   <li>the transaction rolls back and the row vanishes with it - so the
 *       impossible email is impossible, rather than merely unlikely.</li>
 * </ul>
 *
 * <p>An {@code AFTER_COMMIT} listener that queued the mail would lose it if the
 * process died in the window between commit and listener - small, but the whole
 * point of the exercise is that a small window is still a window. Worse, a
 * write attempted after commit has no transaction to join at all, so it needs
 * {@code REQUIRES_NEW} and stops being atomic with the thing it describes.
 *
 * <p>The rule to take away is not "always AFTER_COMMIT"; it is "the phase
 * depends on whether the work is transactional", and moving the send behind a
 * table is what turns an untransactional side effect into a transactional one.
 */
@Service
public class EnrollmentMailListener {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy").withZone(ZoneId.of("Europe/Rome"));

    private MailService mail;
    private StudentRepository students;
    private Logger log;

    public EnrollmentMailListener(MailService mail, StudentRepository students, Logger log) {
        this.mail = mail;
        this.students = students;
        this.log = log;
    }

    /**
     * The confirmation a student expects to find in their inbox a second after
     * clicking "enrol".
     *
     * <p>The dedupe key is the enrollment id: an enrollment is created once, so
     * queuing this twice can only ever be a mistake, and the unique constraint
     * turns that mistake into a no-op rather than a second email.
     */
    @EventListener
    public void onEnrollmentCreated(EnrollmentCreatedEvent event) {
        if (event.getStudentEmail() == null) {
            // Not an exception: a student with no address is a data problem, not
            // a reason to roll back their enrollment. Loud enough to fix, quiet
            // enough not to break anything.
            log.warn("Enrollment {} has no student email - no confirmation will be sent",
                    event.getEnrollmentId());
            return;
        }

        String studentName = students.findByStudentNumber(event.getStudentNumber())
                .map(Student::fullName)
                .orElse("student");

        Map<String, String> model = baseModel(event.getStudentNumber(), studentName);
        model.put("courseCode", event.getCourseCode());
        model.put("courseTitle", event.getCourseTitle());
        model.put("enrolledOn", DATE_FORMAT.format(event.getOccurredAt()));

        mail.enqueueTemplate(
                MailTemplates.ENROLLMENT_CONFIRMED,
                event.getStudentEmail(),
                studentName,
                model,
                "enrollment-confirmed:" + event.getEnrollmentId());
    }

    /**
     * Exam results: three different messages, chosen here rather than by an
     * {@code if} inside one template.
     *
     * <p>Keeping the branch in Java and the words in files is the split that
     * scales. A template language with conditionals in it becomes a second,
     * untested program that happens to live in the resources folder.
     */
    @EventListener
    public void onGradeRecorded(GradeRecordedEvent event) {
        Optional<Student> student = students.findByStudentNumber(event.getStudentNumber());
        if (!student.isPresent() || student.get().getEmail() == null) {
            log.warn("No address for student {} - no exam-result mail will be sent",
                    event.getStudentNumber());
            return;
        }

        // NOTE: GradeRecordedEvent carries a student NUMBER but no address, so
        // this observer has to go back to the database for one. That is a design
        // smell in the EVENT, not here: an event should carry everything its
        // subscribers need, or every subscriber pays for a query. It is left as
        // it is because changing a published event's shape is exactly the kind
        // of decision that deserves its own discussion - see the exercises.
        String recipient = student.get().getEmail().getValue();
        String name = student.get().fullName();

        Map<String, String> model = baseModel(event.getStudentNumber(), name);
        model.put("courseCode", event.getCourseCode());
        model.put("recordedOn", DATE_FORMAT.format(event.getOccurredAt()));
        model.put("grade", event.getGrade() == null ? "-" : String.valueOf(event.getGrade()));

        if (!event.isPassed()) {
            // No dedupe key. An exam can be failed, retaken and failed again,
            // so there is no natural key that stays unique - and inventing one
            // from the clock would be a key that prevents nothing. A dedupe key
            // is only worth having when the domain really does say "once".
            mail.enqueueTemplate(MailTemplates.GRADE_FAILED, recipient, name, model, null);
            return;
        }

        String template = event.isWithHonours() ? MailTemplates.GRADE_HONOURS : MailTemplates.GRADE_PASSED;
        mail.enqueueTemplate(template, recipient, name, model,
                // A passed enrollment is terminal, so this one does have a
                // natural key.
                "grade-passed:" + event.getEnrollmentId());
    }

    /**
     * The values every template shares.
     *
     * <p>{@code LinkedHashMap} so the keys keep insertion order: the only place
     * that order shows up is the "known keys" list in a rendering error, and a
     * predictable list is easier to scan than a hashed one.
     */
    private Map<String, String> baseModel(String studentNumber, String studentName) {
        Map<String, String> model = new LinkedHashMap<>();
        model.put("studentNumber", studentNumber);
        model.put("studentName", studentName);
        return model;
    }
}
