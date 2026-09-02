package it.unicam.cs.enrollment.exercises;

import it.unicam.cs.enrollment.domain.model.AcademicTitle;
import it.unicam.cs.enrollment.domain.model.Course;
import it.unicam.cs.enrollment.domain.model.Email;
import it.unicam.cs.enrollment.domain.model.Enrollment;
import it.unicam.cs.enrollment.domain.model.EnrollmentStatus;
import it.unicam.cs.enrollment.domain.model.Professor;
import it.unicam.cs.enrollment.domain.model.Semester;
import it.unicam.cs.enrollment.domain.model.Student;
import it.unicam.cs.enrollment.exception.BusinessRuleViolationException;
import it.unicam.cs.enrollment.exception.DuplicateResourceException;
import it.unicam.cs.enrollment.exception.ResourceNotFoundException;
import it.unicam.cs.enrollment.repository.CourseRepository;
import it.unicam.cs.enrollment.repository.EnrollmentRepository;
import it.unicam.cs.enrollment.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Specification for Exercise 3. Read these before writing the implementation:
 * they are the requirements, expressed precisely.
 *
 * <p>The clock is pinned, so "is the window open" is deterministic. Strictness
 * is LENIENT on purpose - a correct implementation may short-circuit early and
 * never reach some of the stubs, and that should not be a failure.
 */
@Tag("exercise")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Exercise 3: transferring a student between courses")
class Ex3TransferServiceTest {

    private static final Instant NOW = Instant.parse("2025-09-15T10:00:00Z");
    private static final Long STUDENT_ID = 1L;
    private static final Long FROM_ID = 10L;
    private static final Long TO_ID = 20L;

    @Mock private StudentRepository studentRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private EnrollmentRepository enrollmentRepository;

    private Ex3TransferService service;
    private Student student;
    private Course from;
    private Course to;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new Ex3TransferService(
                studentRepository, courseRepository, enrollmentRepository, clock);

        student = new Student("100001", "Luca", "Ferrari",
                Email.of("luca.ferrari@studenti.unicam.it"),
                LocalDate.of(2004, 3, 14), 2023);
        from = aCourse("CS101", 100, NOW.minus(7, ChronoUnit.DAYS), NOW.plus(7, ChronoUnit.DAYS));
        to = aCourse("CS201", 100, NOW.minus(7, ChronoUnit.DAYS), NOW.plus(7, ChronoUnit.DAYS));

        when(studentRepository.findById(STUDENT_ID)).thenReturn(Optional.of(student));
        when(courseRepository.findById(FROM_ID)).thenReturn(Optional.of(from));
        when(courseRepository.findByIdWithPessimisticLock(TO_ID)).thenReturn(Optional.of(to));
        when(courseRepository.findById(TO_ID)).thenReturn(Optional.of(to));
        when(enrollmentRepository.findByStudentAndCourse(STUDENT_ID, FROM_ID))
                .thenReturn(Optional.of(Enrollment.create(student, from, NOW)));
        when(enrollmentRepository.findByStudentAndCourse(STUDENT_ID, TO_ID))
                .thenReturn(Optional.empty());
        when(enrollmentRepository.countOccupiedSeats(TO_ID)).thenReturn(0L);
        when(enrollmentRepository.save(any(Enrollment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Course aCourse(String code, int capacity, Instant opens, Instant closes) {
        Professor professor = new Professor("P001", "Elena", "Bianchi",
                Email.of("elena.bianchi@unicam.it"),
                AcademicTitle.FULL_PROFESSOR, "Computer Science");
        return new Course(code, code + " title", 6, capacity,
                Semester.FALL, 2025, professor, opens, closes);
    }

    // ------------------------------------------------------------------
    // The happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("creates an ACTIVE enrollment in the target course")
    void createsNewEnrollment() {
        Enrollment result = service.transfer(STUDENT_ID, FROM_ID, TO_ID);

        assertThat(result).isNotNull();
        assertThat(result.getCourse()).isSameAs(to);
        assertThat(result.getStudent()).isSameAs(student);
        assertThat(result.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
        verify(enrollmentRepository).save(any(Enrollment.class));
    }

    @Test
    @DisplayName("withdraws the source enrollment")
    void withdrawsSource() {
        Enrollment source = Enrollment.create(student, from, NOW);
        when(enrollmentRepository.findByStudentAndCourse(STUDENT_ID, FROM_ID))
                .thenReturn(Optional.of(source));

        service.transfer(STUDENT_ID, FROM_ID, TO_ID);

        assertThat(source.getStatus())
                .as("the old enrollment must be WITHDRAWN, freeing its seat")
                .isEqualTo(EnrollmentStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("locks the target course before counting seats")
    void locksTarget() {
        service.transfer(STUDENT_ID, FROM_ID, TO_ID);

        verify(courseRepository)
                .findByIdWithPessimisticLock(TO_ID);
    }

    // ------------------------------------------------------------------
    // The rules
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unknown student -> ResourceNotFoundException")
    void unknownStudent() {
        when(studentRepository.findById(STUDENT_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.transfer(STUDENT_ID, FROM_ID, TO_ID));
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("unknown target course -> ResourceNotFoundException")
    void unknownTargetCourse() {
        when(courseRepository.findByIdWithPessimisticLock(TO_ID)).thenReturn(Optional.empty());
        when(courseRepository.findById(TO_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.transfer(STUDENT_ID, FROM_ID, TO_ID));
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("transferring to the same course is refused")
    void sameCourse() {
        assertThatExceptionOfType(BusinessRuleViolationException.class)
                .isThrownBy(() -> service.transfer(STUDENT_ID, FROM_ID, FROM_ID));
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("no enrollment in the source course -> ResourceNotFoundException")
    void notEnrolledInSource() {
        when(enrollmentRepository.findByStudentAndCourse(STUDENT_ID, FROM_ID))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.transfer(STUDENT_ID, FROM_ID, TO_ID));
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("source enrollment is not ACTIVE -> BusinessRuleViolationException")
    void sourceNotActive() {
        Enrollment completed = Enrollment.create(student, from, NOW);
        completed.recordPass(30, false, NOW);
        when(enrollmentRepository.findByStudentAndCourse(STUDENT_ID, FROM_ID))
                .thenReturn(Optional.of(completed));

        assertThatExceptionOfType(BusinessRuleViolationException.class)
                .isThrownBy(() -> service.transfer(STUDENT_ID, FROM_ID, TO_ID));
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("already enrolled in the target -> DuplicateResourceException")
    void alreadyInTarget() {
        when(enrollmentRepository.findByStudentAndCourse(STUDENT_ID, TO_ID))
                .thenReturn(Optional.of(Enrollment.create(student, to, NOW)));

        assertThatExceptionOfType(DuplicateResourceException.class)
                .isThrownBy(() -> service.transfer(STUDENT_ID, FROM_ID, TO_ID));
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("target course is full -> BusinessRuleViolationException")
    void targetFull() {
        Course tiny = aCourse("CS201", 2, NOW.minus(7, ChronoUnit.DAYS), NOW.plus(7, ChronoUnit.DAYS));
        when(courseRepository.findByIdWithPessimisticLock(TO_ID)).thenReturn(Optional.of(tiny));
        when(courseRepository.findById(TO_ID)).thenReturn(Optional.of(tiny));
        when(enrollmentRepository.countOccupiedSeats(TO_ID)).thenReturn(2L);

        assertThatExceptionOfType(BusinessRuleViolationException.class)
                .isThrownBy(() -> service.transfer(STUDENT_ID, FROM_ID, TO_ID));
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("target enrollment window is closed -> BusinessRuleViolationException")
    void targetWindowClosed() {
        Course closed = aCourse("CS201", 100,
                NOW.minus(30, ChronoUnit.DAYS), NOW.minus(10, ChronoUnit.DAYS));
        when(courseRepository.findByIdWithPessimisticLock(TO_ID)).thenReturn(Optional.of(closed));
        when(courseRepository.findById(TO_ID)).thenReturn(Optional.of(closed));

        assertThatExceptionOfType(BusinessRuleViolationException.class)
                .isThrownBy(() -> service.transfer(STUDENT_ID, FROM_ID, TO_ID));
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("nothing is saved when any rule fails")
    void noPartialWork() {
        when(enrollmentRepository.countOccupiedSeats(anyLong())).thenReturn(999L);

        // Deliberately NOT RuntimeException: an unimplemented stub throws
        // UnsupportedOperationException, which is also a RuntimeException, and
        // this test would pass against code that does nothing at all. A test
        // that passes for the wrong reason is worse than no test.
        assertThatExceptionOfType(BusinessRuleViolationException.class)
                .isThrownBy(() -> service.transfer(STUDENT_ID, FROM_ID, TO_ID));
        verify(enrollmentRepository, never()).save(any());
    }
}
