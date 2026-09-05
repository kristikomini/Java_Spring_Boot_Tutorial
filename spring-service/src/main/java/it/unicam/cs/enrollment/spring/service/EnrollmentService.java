package it.unicam.cs.enrollment.spring.service;

import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.Enrollment;
import it.unicam.cs.enrollment.spring.domain.EnrollmentStatus;
import it.unicam.cs.enrollment.spring.domain.Student;
import it.unicam.cs.enrollment.spring.exception.BusinessRuleViolationException;
import it.unicam.cs.enrollment.spring.exception.DuplicateResourceException;
import it.unicam.cs.enrollment.spring.exception.ResourceNotFoundException;
import it.unicam.cs.enrollment.spring.notification.EnrollmentCreatedEvent;
import it.unicam.cs.enrollment.spring.repository.CourseRepository;
import it.unicam.cs.enrollment.spring.repository.EnrollmentRepository;
import it.unicam.cs.enrollment.spring.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * ============================================================================
 * THE SAME EIGHT BUSINESS RULES, ON THE OTHER FRAMEWORK
 * ============================================================================
 * Diff {@link #enroll} against
 * it.unicam.cs.enrollment.service.EnrollmentService.enroll. The two method
 * bodies are line-for-line the same shape, in the same order, raising the same
 * exceptions with the same error codes. That is the point of this module: the
 * business logic did not care which framework it was in.
 *
 * <p>WHAT CHANGED, exactly, and nothing else:
 *
 * <pre>
 *   Jakarta EE                              Spring
 *   -------------------------------------   -------------------------------------
 *   {@literal @}ApplicationScoped                      {@literal @}Service
 *   {@literal @}Inject on the constructor              nothing (single constructor is enough)
 *   jakarta.transaction.Transactional       org.springframework...Transactional
 *   Event<T>.fire(...)                      ApplicationEventPublisher (not used here)
 *   {@literal @}Loggable interceptor                   an ordinary Logger field
 * </pre>
 *
 * <p>TWO NOTES ON {@code @Transactional}, because they are asked in interviews.
 *
 * <p>First, the DEFAULT ROLLBACK RULE is the same in both: roll back on
 * unchecked exceptions, commit on checked ones. People expect Spring to differ
 * here and it does not. The attribute names differ ({@code rollbackFor} versus
 * {@code rollbackOn}) and that is all. Since every exception thrown below is
 * unchecked, the rollback happens either way.
 *
 * <p>Second, THE SELF-INVOCATION TRAP is identical and is worth being able to
 * describe. Both containers implement {@code @Transactional} with a PROXY: the
 * bean injected elsewhere is not this object, it is a wrapper that opens a
 * transaction and then delegates. So if {@link #enroll} called another
 * {@code @Transactional} method on {@code this}, the call would go straight to
 * the real object and bypass the wrapper entirely - no new transaction, no
 * warning, no error. It is the reason {@code private @Transactional} silently
 * does nothing in both frameworks. Fieldbook chapter 11 has the diagram.
 */
@Service
public class EnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentService.class);

    /**
     * The statuses that hold a seat, computed once. Passed into the count query
     * so the rule lives in the enum rather than in a JPQL string.
     */
    private static final List<EnrollmentStatus> OCCUPYING_STATUSES =
            List.of(EnrollmentStatus.ACTIVE, EnrollmentStatus.FAILED);

    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final Clock clock;

    /**
     * The Spring answer to the CDI {@code Event<T>} the Jakarta EE service
     * injects.
     *
     * <p>Publishing to an in-process publisher rather than calling the HTTP
     * client directly is the point: this service does not know that
     * notifications now live on another machine. The LISTENER knows, and the
     * listener is the only thing that would change if they moved back.
     */
    private final ApplicationEventPublisher events;

    /**
     * CONSTRUCTOR INJECTION, with no annotation at all.
     *
     * <p>Since Spring 4.3 a bean with exactly one constructor needs no
     * {@code @Autowired} - the container has no other candidate to choose from.
     * The Jakarta EE version does need {@code @Inject}, which is the only
     * difference.
     *
     * <p>Constructor injection over field injection, for three reasons that are
     * worth being able to list: the fields can be {@code final}, so the object
     * cannot be half-built; a test can construct it with mocks and no container
     * at all (see EnrollmentServiceTest); and a constructor with nine parameters
     * is visibly a class doing too much, whereas nine {@code @Autowired} fields
     * hide it.
     */
    public EnrollmentService(StudentRepository studentRepository,
                             CourseRepository courseRepository,
                             EnrollmentRepository enrollmentRepository,
                             Clock clock,
                             ApplicationEventPublisher events) {
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Enroll a student on a course, subject to every rule the domain has.
     *
     * <p>THE ORDER OF THE CHECKS IS THE DESIGN. The lock is taken on the course
     * BEFORE the seats are counted, and it is held until the method returns, so
     * no other transaction can slip an insert between the count and the save.
     * Reverse those two lines and the application still passes every test and
     * oversells the last seat under load - a bug that only appears with real
     * concurrency, which is precisely why fieldbook chapter 11 spends a chapter
     * on it.
     */
    @Transactional
    public Enrollment enroll(Long studentId, Long courseId) {
        Instant now = clock.instant();

        // 1. The student must exist...
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> ResourceNotFoundException.of("Student", studentId));

        // 2. ...and be allowed to enroll at all.
        if (!student.canEnroll()) {
            throw BusinessRuleViolationException.studentNotEligible(
                    student.getStudentNumber(), student.getStatus().name());
        }

        // 3. SELECT ... FOR UPDATE. Everything after this line is serialised
        //    against other transactions touching the same course.
        Course course = courseRepository.findByIdForUpdate(courseId)
                .orElseThrow(() -> ResourceNotFoundException.of("Course", courseId));

        // 4. The window is a rule the Course owns; the clock is an input.
        if (!course.isEnrollmentOpen(now)) {
            throw BusinessRuleViolationException.enrollmentWindowClosed(course.getCode());
        }

        // 5. A friendly 409 for the duplicate. The unique constraint on
        //    (student_id, course_id) is what actually guarantees it - this check
        //    only makes the error readable.
        enrollmentRepository.findByStudentIdAndCourseId(studentId, courseId)
                .ifPresent(existing -> {
                    throw new DuplicateResourceException(
                            "Student " + student.getStudentNumber()
                                    + " is already enrolled in course " + course.getCode()
                                    + " (status: " + existing.getStatus() + ")");
                });

        // 6. The seat count, from the database, under the lock taken in step 3.
        long occupied = enrollmentRepository.countOccupiedSeats(courseId, OCCUPYING_STATUSES);
        if (occupied >= course.getCapacity()) {
            throw BusinessRuleViolationException.courseFull(course.getCode(), course.getCapacity());
        }

        // 7. Prerequisites.
        verifyPrerequisites(student, course);

        // 8. Only now is anything written.
        Enrollment enrollment = Enrollment.create(student, course, now);
        enrollmentRepository.save(enrollment);

        // saveAndFlush would do this in one call; the explicit flush is kept to
        // mirror the Jakarta EE version. It forces the INSERT now rather than at
        // commit, so a unique-constraint violation surfaces HERE, inside this
        // method, where the stack trace still points at the enrollment - instead
        // of at whatever line happened to trigger the flush later.
        enrollmentRepository.flush();

        log.info("Student {} enrolled in course {} ({} of {} seats now taken)",
                student.getStudentNumber(), course.getCode(),
                occupied + 1, course.getCapacity());

        // THE SEAM. The Jakarta EE version fires a CDI event here and a mail
        // listener observes it in the same JVM. This publishes to the Spring
        // in-process publisher, and EnrollmentEventPublisher - annotated
        // @TransactionalEventListener(AFTER_COMMIT) - turns it into an HTTP call
        // to a separate service.
        //
        // NOTICE WHAT THIS METHOD DOES NOT KNOW. There is no URL here, no
        // timeout, no retry, no circuit breaker, and no mention that a network
        // exists. Publishing an event is the same line it would be if the
        // listener were still in this JVM, which is what makes the boundary
        // movable: extracting notifications required changing the LISTENER, not
        // the business logic.
        //
        // That is the practical version of what chapter 33 means by cutting
        // where the business is loosely coupled. The seam was already here; the
        // extraction only had to follow it.
        events.publishEvent(EnrollmentCreatedEvent.of(
                enrollment.getId(),
                student.getStudentNumber(),
                student.getEmail() != null ? student.getEmail().getValue() : "unknown@unicam.it",
                course.getCode(),
                course.getTitle(),
                now));

        return enrollment;
    }

    private void verifyPrerequisites(Student student, Course course) {
        List<String> missing = course.getPrerequisites().stream()
                .map(Course::getCode)
                .filter(code -> enrollmentRepository
                        .countCompletedByCourseCode(student.getId(), code) == 0)
                .toList();

        if (!missing.isEmpty()) {
            throw BusinessRuleViolationException.prerequisitesNotMet(
                    course.getCode(), String.join(", ", missing));
        }
    }

    /**
     * {@code readOnly = true} is the one Spring transaction attribute with no
     * direct Jakarta EE equivalent, and it earns its place twice over.
     *
     * <p>Hibernate skips dirty checking for the whole persistence context, which
     * on a list endpoint means it does not walk every loaded entity comparing it
     * against its snapshot at flush time. And the JDBC connection is marked
     * read-only, which on a replicated PostgreSQL lets the driver route the query
     * to a replica.
     *
     * <p>It is not a security control. A read-only transaction will happily
     * execute a native UPDATE; it is a hint about intent, not a permission.
     */
    @Transactional(readOnly = true)
    public Enrollment findById(Long enrollmentId) {
        return enrollmentRepository.findByIdWithDetails(enrollmentId)
                .orElseThrow(() -> ResourceNotFoundException.of("Enrollment", enrollmentId));
    }

    @Transactional(readOnly = true)
    public List<Enrollment> findByCourse(Long courseId, EnrollmentStatus status) {
        return enrollmentRepository.findByCourseAndStatus(
                courseId, status != null ? status : EnrollmentStatus.ACTIVE);
    }

    /**
     * Withdraw, releasing the seat.
     *
     * <p>There is no repository save call, and that is not an omission. The
     * entity is MANAGED inside this transaction, so Hibernate compares it against
     * its loaded snapshot at commit and writes an UPDATE for what changed. Dirty
     * checking, and it works identically in both frameworks. Fieldbook chapter 09
     * is the long version; the short version is that {@code save()} on an
     * already-managed entity is a no-op that many codebases call anyway.
     */
    @Transactional
    public Enrollment withdraw(Long enrollmentId) {
        Enrollment enrollment = findById(enrollmentId);
        try {
            enrollment.withdraw(clock.instant());
        } catch (IllegalStateException e) {
            throw BusinessRuleViolationException.illegalStateTransition(e.getMessage());
        }
        log.info("Enrollment {} withdrawn; a seat was released on course {}",
                enrollmentId, enrollment.getCourse().getCode());
        return enrollment;
    }

    /**
     * Record a passing grade.
     *
     * <p>The entity raises plain Java exceptions - IllegalArgumentException for a
     * grade out of range, IllegalStateException for an impossible transition -
     * because the domain must not know what HTTP is. Translating them into
     * business exceptions is this layer doing its job. It is the same reason the
     * controller does not throw ResponseStatusException: each layer speaks its
     * own vocabulary and translates at the boundary.
     */
    @Transactional
    public Enrollment recordPass(Long enrollmentId, int grade, boolean withHonours) {
        Enrollment enrollment = findById(enrollmentId);
        try {
            enrollment.recordPass(grade, withHonours, clock.instant());
        } catch (IllegalArgumentException e) {
            throw BusinessRuleViolationException.invalidGrade(e.getMessage());
        } catch (IllegalStateException e) {
            throw BusinessRuleViolationException.illegalStateTransition(e.getMessage());
        }
        log.info("Recorded grade {} for enrollment {}",
                enrollment.formattedGrade(), enrollmentId);
        return enrollment;
    }
}
