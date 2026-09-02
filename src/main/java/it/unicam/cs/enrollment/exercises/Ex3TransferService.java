package it.unicam.cs.enrollment.exercises;

import it.unicam.cs.enrollment.domain.model.Course;
import it.unicam.cs.enrollment.domain.model.Enrollment;
import it.unicam.cs.enrollment.domain.model.Student;
import it.unicam.cs.enrollment.exception.BusinessRuleViolationException;
import it.unicam.cs.enrollment.exception.DuplicateResourceException;
import it.unicam.cs.enrollment.exception.ResourceNotFoundException;
import it.unicam.cs.enrollment.repository.CourseRepository;
import it.unicam.cs.enrollment.repository.EnrollmentRepository;
import it.unicam.cs.enrollment.repository.StudentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * EXERCISE 3 - A use case with a transaction (the service layer)
 * =============================================================================
 * Difficulty: the real one. This is the exercise worth spending time on.
 *
 * <p>Run the tests for this exercise with:
 * <pre>mvn test -Pexercises -Dtest=Ex3TransferServiceTest</pre>
 *
 * <h2>What to do</h2>
 * Implement {@link #transfer(Long, Long, Long)}: move a student out of one
 * course and into another, as a single atomic operation. Either both halves
 * happen or neither does.
 *
 * <h2>The rules, in order</h2>
 * <ol>
 *   <li>The student must exist, else {@code ResourceNotFoundException.of("Student", id)}.</li>
 *   <li>Both courses must exist, else {@code ResourceNotFoundException.of("Course", id)}.</li>
 *   <li>Transferring to the same course is nonsense:
 *       {@code BusinessRuleViolationException.illegalStateTransition(...)}.</li>
 *   <li>The student must have an ACTIVE enrollment in the source course. If
 *       there is no enrollment at all, that is
 *       {@code ResourceNotFoundException.of("Enrollment", ...)}; if it exists
 *       but is not ACTIVE, that is an illegal state transition.</li>
 *   <li>The student must not already be enrolled in the target course
 *       - {@link DuplicateResourceException}.</li>
 *   <li>The target course's enrollment window must be open, else
 *       {@code BusinessRuleViolationException.enrollmentWindowClosed(code)}.</li>
 *   <li>The target course must have a free seat, else
 *       {@code BusinessRuleViolationException.courseFull(code, capacity)}.</li>
 *   <li>Only then: withdraw from the source, create the new enrollment, save it,
 *       and return it.</li>
 * </ol>
 *
 * <h2>What you are practising</h2>
 * <ul>
 *   <li><strong>Atomicity.</strong> {@code @Transactional} is already on the
 *       method. Because of it, a rule violation thrown at step 7 rolls back the
 *       withdrawal from step 8 - as long as you throw a <em>runtime</em>
 *       exception. Chapter 5 explains why a checked one would not.</li>
 *   <li><strong>Check before you mutate.</strong> Validate everything first,
 *       then change state. It keeps the method readable and means the rollback
 *       is a safety net rather than your primary mechanism.</li>
 *   <li><strong>Locking.</strong> Use
 *       {@code courseRepository.findByIdWithPessimisticLock} for the
 *       <em>target</em> course. The seat count you read must not change under
 *       you before you insert. Chapter 3 covers why.</li>
 *   <li><strong>Rich domain model.</strong> Do not set status fields by hand.
 *       Call {@code enrollment.withdraw(now)} and let the entity enforce its own
 *       state machine, and {@code Enrollment.create(student, course, now)} to
 *       build the new one.</li>
 * </ul>
 *
 * <h2>Hints</h2>
 * <ul>
 *   <li>{@code clock.instant()} gives you {@code now}. Never
 *       {@code Instant.now()} - the tests pin the clock.</li>
 *   <li>{@code enrollmentRepository.findByStudentAndCourse(studentId, courseId)}
 *       returns {@code Optional<Enrollment>}.</li>
 *   <li>{@code enrollmentRepository.countOccupiedSeats(courseId)} versus
 *       {@code course.getCapacity()} decides "is it full".</li>
 *   <li>Model the shape on {@code EnrollmentService.enroll} - it solves most of
 *       the same problems and is the reference implementation to learn from.</li>
 * </ul>
 */
@ApplicationScoped
public class Ex3TransferService {

    @Inject
    StudentRepository studentRepository;

    @Inject
    CourseRepository courseRepository;

    @Inject
    EnrollmentRepository enrollmentRepository;

    @Inject
    Clock clock;

    /** CDI needs a no-arg constructor, and RESTEasy needs it to be public. */
    public Ex3TransferService() {
    }

    /** Constructor injection, used by the tests. */
    public Ex3TransferService(StudentRepository studentRepository,
                              CourseRepository courseRepository,
                              EnrollmentRepository enrollmentRepository,
                              Clock clock) {
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.clock = clock;
    }

    /**
     * Moves a student from one course to another atomically.
     *
     * @param studentId    the student
     * @param fromCourseId the course to leave
     * @param toCourseId   the course to join
     * @return the newly created enrollment in the target course
     */
    @Transactional
    public Enrollment transfer(Long studentId, Long fromCourseId, Long toCourseId) {
        // TODO Exercise 3: implement the eight steps described above.
        //
        // Suggested skeleton:
        //   Instant now = clock.instant();
        //   Student student = studentRepository.findById(studentId)
        //           .orElseThrow(() -> ResourceNotFoundException.of("Student", studentId));
        //   ... validate ...
        //   source.withdraw(now);
        //   Enrollment moved = Enrollment.create(student, target, now);
        //   return enrollmentRepository.save(moved);
        throw new UnsupportedOperationException(
                "Exercise 3 not implemented yet - see the Javadoc above for the rules.");
    }

    // Suppress unused-import warnings for the exception types you are expected
    // to use. Delete this method once you have implemented transfer().
    @SuppressWarnings("unused")
    private void referencedByTheExercise(Student s, Course c,
                                         BusinessRuleViolationException b,
                                         DuplicateResourceException d) {
    }
}
