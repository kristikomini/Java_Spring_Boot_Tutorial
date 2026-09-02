package it.unicam.cs.enrollment.exercises;

import it.unicam.cs.enrollment.domain.model.AcademicTitle;
import it.unicam.cs.enrollment.domain.model.Course;
import it.unicam.cs.enrollment.domain.model.Email;
import it.unicam.cs.enrollment.domain.model.Professor;
import it.unicam.cs.enrollment.domain.model.Semester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static it.unicam.cs.enrollment.exercises.Ex2CourseWindow.Window;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Specification for Exercise 2. These tests are the contract - read them before
 * you write any code, and make them pass one at a time.
 *
 * <p>Notice how many of them sit exactly on a boundary. That is deliberate: the
 * middle of a range almost never breaks, and the edges almost always do.
 */
@Tag("exercise")
@DisplayName("Exercise 2: enrollment window state")
class Ex2CourseWindowTest {

    private static final Instant OPENS = Instant.parse("2025-09-01T00:00:00Z");
    private static final Instant CLOSES = Instant.parse("2025-10-01T00:00:00Z");

    private Course aCourse() {
        Professor professor = new Professor("P001", "Elena", "Bianchi",
                Email.of("elena.bianchi@unicam.it"),
                AcademicTitle.FULL_PROFESSOR, "Computer Science");
        return new Course("CS401", "Enterprise Software Architecture", 6, 30,
                Semester.FALL, 2025, professor, OPENS, CLOSES);
    }

    @Test
    @DisplayName("well before the window opens -> NOT_YET_OPEN")
    void beforeWindow() {
        assertThat(Ex2CourseWindow.windowFor(aCourse(), OPENS.minus(10, ChronoUnit.DAYS)))
                .isEqualTo(Window.NOT_YET_OPEN);
    }

    @Test
    @DisplayName("one nanosecond before opening -> NOT_YET_OPEN")
    void justBeforeOpening() {
        assertThat(Ex2CourseWindow.windowFor(aCourse(), OPENS.minusNanos(1)))
                .isEqualTo(Window.NOT_YET_OPEN);
    }

    @Test
    @DisplayName("exactly at opensAt -> OPEN (the interval is inclusive here)")
    void exactlyAtOpening() {
        assertThat(Ex2CourseWindow.windowFor(aCourse(), OPENS))
                .isEqualTo(Window.OPEN);
    }

    @Test
    @DisplayName("in the middle -> OPEN")
    void insideWindow() {
        assertThat(Ex2CourseWindow.windowFor(aCourse(), OPENS.plus(5, ChronoUnit.DAYS)))
                .isEqualTo(Window.OPEN);
    }

    @Test
    @DisplayName("one nanosecond before closing -> OPEN")
    void justBeforeClosing() {
        assertThat(Ex2CourseWindow.windowFor(aCourse(), CLOSES.minusNanos(1)))
                .isEqualTo(Window.OPEN);
    }

    @Test
    @DisplayName("exactly at closesAt -> CLOSED (the interval is exclusive here)")
    void exactlyAtClosing() {
        assertThat(Ex2CourseWindow.windowFor(aCourse(), CLOSES))
                .isEqualTo(Window.CLOSED);
    }

    @Test
    @DisplayName("well after -> CLOSED")
    void afterWindow() {
        assertThat(Ex2CourseWindow.windowFor(aCourse(), CLOSES.plus(30, ChronoUnit.DAYS)))
                .isEqualTo(Window.CLOSED);
    }

    @Test
    @DisplayName("agrees with Course.isEnrollmentOpen at every instant")
    void agreesWithExistingMethod() {
        Course course = aCourse();
        Instant[] instants = {
                OPENS.minus(1, ChronoUnit.DAYS), OPENS.minusNanos(1), OPENS,
                OPENS.plus(1, ChronoUnit.DAYS), CLOSES.minusNanos(1),
                CLOSES, CLOSES.plus(1, ChronoUnit.DAYS)
        };
        for (Instant instant : instants) {
            boolean openAccordingToDomain = course.isEnrollmentOpen(instant);
            boolean openAccordingToYou =
                    Ex2CourseWindow.windowFor(course, instant) == Window.OPEN;
            assertThat(openAccordingToYou)
                    .as("disagreement at %s", instant)
                    .isEqualTo(openAccordingToDomain);
        }
    }

    @ParameterizedTest(name = "null {0} raises NullPointerException")
    @CsvSource({"course", "now"})
    @DisplayName("null arguments fail loudly")
    void nullArguments(String which) {
        Course course = "course".equals(which) ? null : aCourse();
        Instant now = "now".equals(which) ? null : OPENS;
        assertThatNullPointerException()
                .isThrownBy(() -> Ex2CourseWindow.windowFor(course, now));
    }
}
