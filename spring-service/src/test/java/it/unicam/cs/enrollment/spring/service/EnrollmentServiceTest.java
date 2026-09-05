package it.unicam.cs.enrollment.spring.service;

import it.unicam.cs.enrollment.spring.domain.AcademicTitle;
import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.Email;
import it.unicam.cs.enrollment.spring.domain.Enrollment;
import it.unicam.cs.enrollment.spring.domain.EnrollmentStatus;
import it.unicam.cs.enrollment.spring.domain.Professor;
import it.unicam.cs.enrollment.spring.domain.Semester;
import it.unicam.cs.enrollment.spring.domain.Student;
import it.unicam.cs.enrollment.spring.domain.StudentStatus;
import it.unicam.cs.enrollment.spring.exception.BusinessRuleViolationException;
import it.unicam.cs.enrollment.spring.exception.DuplicateResourceException;
import it.unicam.cs.enrollment.spring.exception.ResourceNotFoundException;
import it.unicam.cs.enrollment.spring.notification.EnrollmentCreatedEvent;
import it.unicam.cs.enrollment.spring.repository.CourseRepository;
import it.unicam.cs.enrollment.spring.repository.EnrollmentRepository;
import it.unicam.cs.enrollment.spring.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ============================================================================
 * A UNIT TEST - NO SPRING, NO DATABASE, NO HTTP
 * ============================================================================
 * There is no {@code @SpringBootTest} here and that is the entire point. The
 * service is constructed with {@code new} and handed three mocks and a fixed
 * clock. The whole class runs in a few milliseconds because nothing starts.
 *
 * <p>This is only possible because of CONSTRUCTOR INJECTION. Had the service
 * used {@code @Autowired} fields, there would be no way to supply the
 * collaborators without a container or reflection - which is the practical
 * reason to prefer constructors, over and above the design argument.
 *
 * <p>THE FIXED CLOCK is the other half. Every rule about the enrollment window
 * is testable at any date, in any order, in either hemisphere, because time is
 * an argument rather than an ambient fact. Fieldbook chapter 20 calls a test
 * that depends on the real clock a test that will fail one morning for reasons
 * nobody can reproduce.
 *
 * <p>WHAT THIS TEST CANNOT PROVE, and it matters: the mocks always agree with
 * the service. {@code countOccupiedSeats} returns whatever this file says, so
 * the JPQL is never executed and a typo in it would pass every assertion here.
 * That is why EnrollmentApiIT exists. A unit test proves the LOGIC; only a test
 * against a real database proves the QUERIES. Chapter 20 calls the belief that
 * the first covers the second "the test that lies".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnrollmentService - the eight business rules")
class EnrollmentServiceTest {

    /** Inside the enrollment window declared in {@link #openCourse}. */
    private static final Instant NOW = Instant.parse("2026-09-05T10:00:00Z");

    @Mock
    private StudentRepository studentRepository;
    @Mock
    private CourseRepository courseRepository;
    @Mock
    private EnrollmentRepository enrollmentRepository;

    /**
     * The event publisher, mocked.
     *
     * <p>Worth noticing how little changed when notifications moved to another
     * machine. This test gained ONE mock. It knows nothing about HTTP, timeouts,
     * retries or circuit breakers, because the service does not either - it
     * publishes an event and the listener deals with the network.
     *
     * <p>That is the practical payoff of publishing an event rather than calling
     * the client directly: the business rules stayed unit-testable across a
     * change that turned a method call into a distributed one.
     */
    @Mock
    private ApplicationEventPublisher events;

    private EnrollmentService service;

    @BeforeEach
    void setUp() {
        service = new EnrollmentService(
                studentRepository,
                courseRepository,
                enrollmentRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                events);
    }

    @Nested
    @DisplayName("enroll()")
    class Enroll {

        @Test
        @DisplayName("creates the enrollment when every rule passes")
        void enrollsWhenAllRulesPass() {
            Student student = activeStudent();
            Course course = openCourse(30);

            when(studentRepository.findById(1L)).thenReturn(Optional.of(student));
            when(courseRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(course));
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 2L))
                    .thenReturn(Optional.empty());
            when(enrollmentRepository.countOccupiedSeats(anyLong(), anyList())).thenReturn(5L);

            Enrollment result = service.enroll(1L, 2L);

            assertThat(result.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
            assertThat(result.getStudent()).isSameAs(student);
            assertThat(result.getCourse()).isSameAs(course);
            // The clock, not Instant.now(). A test can assert on it exactly.
            assertThat(result.getEnrolledAt()).isEqualTo(NOW);
            verify(enrollmentRepository).save(any(Enrollment.class));

            // The event is published, with a stable id the receiver can
            // deduplicate on. Asserting the CONTENT matters as much as the call:
            // an event missing its eventId would make every retry look like a
            // new notification.
            ArgumentCaptor<EnrollmentCreatedEvent> captor =
                    ArgumentCaptor.forClass(EnrollmentCreatedEvent.class);
            verify(events).publishEvent(captor.capture());
            assertThat(captor.getValue().eventId()).isNotBlank();
            assertThat(captor.getValue().courseCode()).isEqualTo("CS201");
            assertThat(captor.getValue().studentNumber()).isEqualTo("S1234567");
        }

        @Test
        @DisplayName("publishes NO event when the enrollment is refused")
        void noEventOnFailure() {
            when(studentRepository.findById(1L)).thenReturn(Optional.of(activeStudent()));
            when(courseRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(openCourse(30)));
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 2L))
                    .thenReturn(Optional.empty());
            when(enrollmentRepository.countOccupiedSeats(anyLong(), anyList())).thenReturn(30L);

            assertThatThrownBy(() -> service.enroll(1L, 2L))
                    .isInstanceOf(BusinessRuleViolationException.class);

            // Nobody is emailed about an enrollment that did not happen. In
            // production @TransactionalEventListener(AFTER_COMMIT) is the real
            // guarantee - the event would not fire even if it HAD been published,
            // because the transaction rolled back. This asserts the simpler
            // property: the failing path does not publish at all.
            verify(events, never()).publishEvent(any(EnrollmentCreatedEvent.class));
        }

        @Test
        @DisplayName("takes the pessimistic lock rather than a plain read")
        void locksTheCourseRow() {
            when(studentRepository.findById(1L)).thenReturn(Optional.of(activeStudent()));
            when(courseRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(openCourse(30)));
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 2L))
                    .thenReturn(Optional.empty());
            when(enrollmentRepository.countOccupiedSeats(anyLong(), anyList())).thenReturn(0L);

            service.enroll(1L, 2L);

            // The assertion that protects the concurrency design. If someone
            // "simplifies" this to findById during a refactor, the seat rule
            // silently stops being safe under load and every other test here
            // still passes. This one does not.
            verify(courseRepository).findByIdForUpdate(2L);
            verify(courseRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("rejects a full course with COURSE_FULL and writes nothing")
        void rejectsFullCourse() {
            when(studentRepository.findById(1L)).thenReturn(Optional.of(activeStudent()));
            when(courseRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(openCourse(30)));
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 2L))
                    .thenReturn(Optional.empty());
            when(enrollmentRepository.countOccupiedSeats(anyLong(), anyList())).thenReturn(30L);

            assertThatThrownBy(() -> service.enroll(1L, 2L))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("capacity of 30")
                    .extracting(e -> ((BusinessRuleViolationException) e).getErrorCode())
                    .isEqualTo("COURSE_FULL");

            verify(enrollmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("uses >= so the boundary seat is not oversold")
        void boundaryIsInclusive() {
            // capacity 10, occupied 10: the off-by-one that would sell an
            // eleventh seat. Worth its own test precisely because > and >= look
            // equally plausible in review.
            when(studentRepository.findById(1L)).thenReturn(Optional.of(activeStudent()));
            when(courseRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(openCourse(10)));
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 2L))
                    .thenReturn(Optional.empty());
            when(enrollmentRepository.countOccupiedSeats(anyLong(), anyList())).thenReturn(10L);

            assertThatThrownBy(() -> service.enroll(1L, 2L))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }

        @Test
        @DisplayName("rejects a suspended student before touching the course")
        void rejectsIneligibleStudent() {
            Student suspended = activeStudent();
            suspended.setStatus(StudentStatus.SUSPENDED);
            when(studentRepository.findById(1L)).thenReturn(Optional.of(suspended));

            assertThatThrownBy(() -> service.enroll(1L, 2L))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .extracting(e -> ((BusinessRuleViolationException) e).getErrorCode())
                    .isEqualTo("STUDENT_NOT_ELIGIBLE");

            // Cheap checks first: no lock is taken, so a doomed request never
            // blocks a course row that other students are competing for.
            verify(courseRepository, never()).findByIdForUpdate(anyLong());
        }

        @Test
        @DisplayName("rejects enrollment outside the window")
        void rejectsClosedWindow() {
            Course closed = new Course("CS101", "Programming", 9, 30,
                    Semester.FALL, 2026, professor(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-02-01T00:00:00Z"));   // closed in February

            when(studentRepository.findById(1L)).thenReturn(Optional.of(activeStudent()));
            when(courseRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(closed));

            assertThatThrownBy(() -> service.enroll(1L, 2L))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .extracting(e -> ((BusinessRuleViolationException) e).getErrorCode())
                    .isEqualTo("ENROLLMENT_WINDOW_CLOSED");
        }

        @Test
        @DisplayName("rejects a duplicate with 409, not a second row")
        void rejectsDuplicate() {
            Student student = activeStudent();
            Course course = openCourse(30);
            when(studentRepository.findById(1L)).thenReturn(Optional.of(student));
            when(courseRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(course));
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 2L))
                    .thenReturn(Optional.of(Enrollment.create(student, course, NOW)));

            assertThatThrownBy(() -> service.enroll(1L, 2L))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("already enrolled");
        }

        @Test
        @DisplayName("rejects when a prerequisite has not been passed, naming it")
        void rejectsMissingPrerequisite() {
            Course prerequisite = new Course("CS100", "Intro", 6, 100,
                    Semester.FALL, 2025, professor(), NOW.minusSeconds(1), NOW.plusSeconds(1));
            Course course = openCourse(30);
            course.addPrerequisite(prerequisite);

            when(studentRepository.findById(1L)).thenReturn(Optional.of(activeStudent()));
            when(courseRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(course));
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 2L))
                    .thenReturn(Optional.empty());
            when(enrollmentRepository.countOccupiedSeats(anyLong(), anyList())).thenReturn(0L);
            // Never completed CS100.
            when(enrollmentRepository.countCompletedByCourseCode(any(), anyString()))
                    .thenReturn(0L);

            assertThatThrownBy(() -> service.enroll(1L, 2L))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    // Naming the missing course is the difference between an
                    // error a user can act on and one they can only report.
                    .hasMessageContaining("CS100")
                    .extracting(e -> ((BusinessRuleViolationException) e).getErrorCode())
                    .isEqualTo("PREREQUISITES_NOT_MET");
        }

        @Test
        @DisplayName("404s for an unknown student")
        void unknownStudent() {
            when(studentRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.enroll(99L, 2L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Student");
        }
    }

    @Nested
    @DisplayName("withdraw()")
    class Withdraw {

        @Test
        @DisplayName("releases the seat by moving the row to WITHDRAWN")
        void withdrawsActiveEnrollment() {
            Enrollment enrollment = Enrollment.create(activeStudent(), openCourse(30), NOW);
            when(enrollmentRepository.findByIdWithDetails(7L)).thenReturn(Optional.of(enrollment));

            Enrollment result = service.withdraw(7L);

            assertThat(result.getStatus()).isEqualTo(EnrollmentStatus.WITHDRAWN);
            assertThat(result.getStatus().occupiesSeat()).isFalse();
            assertThat(result.getCompletedAt()).isEqualTo(NOW);

            // No save() call, and that is correct: the entity is managed, so
            // dirty checking writes the UPDATE at commit. Asserting the absence
            // documents the intent - see the comment on the service method.
            verify(enrollmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses to withdraw twice, as a 409 rather than a 500")
        void refusesSecondWithdrawal() {
            Enrollment enrollment = Enrollment.create(activeStudent(), openCourse(30), NOW);
            enrollment.withdraw(NOW);
            when(enrollmentRepository.findByIdWithDetails(7L)).thenReturn(Optional.of(enrollment));

            // The entity throws IllegalStateException; the service translates it
            // into a business exception the error handler maps to 409. Without
            // that translation this would surface as an unhandled 500.
            assertThatThrownBy(() -> service.withdraw(7L))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .extracting(e -> ((BusinessRuleViolationException) e).getErrorCode())
                    .isEqualTo("ILLEGAL_STATE_TRANSITION");
        }
    }

    @Nested
    @DisplayName("recordPass()")
    class RecordPass {

        @Test
        @DisplayName("records a pass and completes the enrollment")
        void recordsPass() {
            Enrollment enrollment = Enrollment.create(activeStudent(), openCourse(30), NOW);
            when(enrollmentRepository.findByIdWithDetails(7L)).thenReturn(Optional.of(enrollment));

            Enrollment result = service.recordPass(7L, 30, true);

            assertThat(result.getStatus()).isEqualTo(EnrollmentStatus.COMPLETED);
            assertThat(result.formattedGrade()).isEqualTo("30 e lode");
        }

        @Test
        @DisplayName("rejects honours below 30 with INVALID_GRADE")
        void rejectsHonoursBelowMax() {
            Enrollment enrollment = Enrollment.create(activeStudent(), openCourse(30), NOW);
            when(enrollmentRepository.findByIdWithDetails(7L)).thenReturn(Optional.of(enrollment));

            assertThatThrownBy(() -> service.recordPass(7L, 28, true))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .extracting(e -> ((BusinessRuleViolationException) e).getErrorCode())
                    .isEqualTo("INVALID_GRADE");
        }

        @Test
        @DisplayName("rejects a failing grade as a pass")
        void rejectsGradeBelowPassMark() {
            Enrollment enrollment = Enrollment.create(activeStudent(), openCourse(30), NOW);
            when(enrollmentRepository.findByIdWithDetails(7L)).thenReturn(Optional.of(enrollment));

            assertThatThrownBy(() -> service.recordPass(7L, 17, false))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("between 18 and 30");
        }
    }

    // ------------------------------------------------------------------
    // Fixtures. Plain constructors - no builder, no framework.
    // ------------------------------------------------------------------

    private static Student activeStudent() {
        return new Student("S1234567", "Giulia", "Rossi",
                Email.of("giulia.rossi@studenti.unicam.it"),
                LocalDate.of(2004, 3, 12), 2025);
    }

    private static Professor professor() {
        return new Professor("P0001", "Marco", "Bianchi",
                Email.of("marco.bianchi@unicam.it"),
                AcademicTitle.ASSOCIATE_PROFESSOR, "Computer Science");
    }

    /** A course whose window is open at {@link #NOW}. */
    private static Course openCourse(int capacity) {
        return new Course("CS201", "Algorithms", 9, capacity,
                Semester.FALL, 2026, professor(),
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    @DisplayName("the occupying statuses are exactly ACTIVE and FAILED")
    void occupyingStatuses() {
        // Guards the rule the seat count depends on. If someone decides a
        // WITHDRAWN enrollment should keep its seat, this fails and points at
        // the decision rather than at a mysterious capacity bug.
        assertThat(List.of(EnrollmentStatus.values()))
                .filteredOn(EnrollmentStatus::occupiesSeat)
                .containsExactlyInAnyOrder(EnrollmentStatus.ACTIVE, EnrollmentStatus.FAILED);
    }
}
