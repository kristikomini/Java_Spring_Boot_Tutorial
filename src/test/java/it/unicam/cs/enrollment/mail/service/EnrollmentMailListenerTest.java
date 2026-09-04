package it.unicam.cs.enrollment.mail.service;

import it.unicam.cs.enrollment.domain.event.EnrollmentCreatedEvent;
import it.unicam.cs.enrollment.domain.event.GradeRecordedEvent;
import it.unicam.cs.enrollment.domain.model.Email;
import it.unicam.cs.enrollment.domain.model.Student;
import it.unicam.cs.enrollment.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which template each domain event produces, and which of them carry a dedupe
 * key.
 *
 * <p>Note what is NOT asserted here: any of the actual wording. The words live
 * in files precisely so they can be changed without a code review, and a test
 * that pinned them would make every wording change a test failure - training
 * everyone to update the assertion without reading it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnrollmentMailListener")
class EnrollmentMailListenerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @Mock
    private MailService mail;

    @Mock
    private StudentRepository students;

    @Mock
    private Logger log;

    private EnrollmentMailListener listener;

    @BeforeEach
    void setUp() {
        listener = new EnrollmentMailListener(mail, students, log);

        Student student = new Student("123456", "Mario", "Rossi",
                Email.of("mario@studenti.unicam.it"), LocalDate.of(2000, 5, 17), 2026);
        lenient().when(students.findByStudentNumber("123456")).thenReturn(Optional.of(student));
    }

    private static EnrollmentCreatedEvent enrollmentCreated() {
        return new EnrollmentCreatedEvent(42L, 1L, "123456", "mario@studenti.unicam.it",
                7L, "CS101", "Programming Fundamentals", NOW);
    }

    @Test
    @DisplayName("queues a confirmation keyed by the enrollment id")
    void confirmation() {
        listener.onEnrollmentCreated(enrollmentCreated());

        ArgumentCaptor<Map<String, String>> model = ArgumentCaptor.forClass(Map.class);
        verify(mail).enqueueTemplate(eq(MailTemplates.ENROLLMENT_CONFIRMED),
                eq("mario@studenti.unicam.it"), eq("Mario Rossi"),
                model.capture(), eq("enrollment-confirmed:42"));

        assertThat(model.getValue())
                .containsEntry("courseCode", "CS101")
                .containsEntry("courseTitle", "Programming Fundamentals")
                .containsEntry("studentNumber", "123456")
                .containsKey("enrolledOn");
    }

    @Test
    @DisplayName("sends nothing, and says so, when the student has no address")
    void noAddressOnTheEvent() {
        listener.onEnrollmentCreated(new EnrollmentCreatedEvent(
                42L, 1L, "123456", null, 7L, "CS101", "Programming Fundamentals", NOW));

        // Missing data is not a reason to roll back somebody's enrollment. It is
        // a reason to leave a WARN that someone can act on.
        verify(mail, never()).enqueueTemplate(anyString(), anyString(), any(), anyMap(), any());
        verify(log).warn(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("uses the honours template for 30 e lode")
    void honours() {
        listener.onGradeRecorded(new GradeRecordedEvent(
                42L, "123456", "CS101", 30, true, true, NOW));

        verify(mail).enqueueTemplate(eq(MailTemplates.GRADE_HONOURS), anyString(), anyString(),
                anyMap(), eq("grade-passed:42"));
    }

    @Test
    @DisplayName("uses the ordinary pass template for any other passing grade")
    void passed() {
        listener.onGradeRecorded(new GradeRecordedEvent(
                42L, "123456", "CS101", 27, false, true, NOW));

        ArgumentCaptor<Map<String, String>> model = ArgumentCaptor.forClass(Map.class);
        verify(mail).enqueueTemplate(eq(MailTemplates.GRADE_PASSED), anyString(), anyString(),
                model.capture(), eq("grade-passed:42"));

        assertThat(model.getValue()).containsEntry("grade", "27");
    }

    @Test
    @DisplayName("uses no dedupe key for a failure, because an exam can be failed twice")
    void failed() {
        listener.onGradeRecorded(new GradeRecordedEvent(
                42L, "123456", "CS101", null, false, false, NOW));

        // A key that has to stay unique across retakes has no natural source, and
        // one invented from the clock would prevent nothing. Better to have none
        // than to have one that only looks like protection.
        verify(mail).enqueueTemplate(eq(MailTemplates.GRADE_FAILED), anyString(), anyString(),
                anyMap(), eq((String) null));
    }

    @Test
    @DisplayName("sends nothing when the student cannot be found")
    void unknownStudent() {
        when(students.findByStudentNumber("999999")).thenReturn(Optional.empty());

        listener.onGradeRecorded(new GradeRecordedEvent(
                42L, "999999", "CS101", 30, false, true, NOW));

        verify(mail, never()).enqueueTemplate(anyString(), anyString(), any(), anyMap(), any());
    }
}
