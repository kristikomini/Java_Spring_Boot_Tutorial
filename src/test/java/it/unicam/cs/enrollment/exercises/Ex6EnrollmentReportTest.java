package it.unicam.cs.enrollment.exercises;

import it.unicam.cs.enrollment.domain.model.AcademicTitle;
import it.unicam.cs.enrollment.domain.model.Course;
import it.unicam.cs.enrollment.domain.model.Email;
import it.unicam.cs.enrollment.domain.model.Enrollment;
import it.unicam.cs.enrollment.domain.model.Professor;
import it.unicam.cs.enrollment.domain.model.Semester;
import it.unicam.cs.enrollment.domain.model.Student;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specification for Exercise 6 - the aggregate report.
 *
 * <p>Runs against in-memory H2, like Exercise 1, because the thing under test
 * <em>is</em> the query. There is nothing here a mocked {@code EntityManager}
 * could prove.
 *
 * <p>The fixtures are deliberately shaped around the four things that go wrong
 * with an aggregate query: a group that is below the threshold, a group that is
 * exactly on it, rows that must be filtered out <em>before</em> grouping
 * (withdrawn enrollments), and a course with nothing at all, which must not
 * appear as a zero.
 */
@Tag("exercise")
@DisplayName("Exercise 6: popularCourses (JOIN · GROUP BY · HAVING, on H2)")
class Ex6EnrollmentReportTest {

    private static final Instant AT = Instant.parse("2026-03-01T10:00:00Z");

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private Ex6EnrollmentReport report;
    private Professor professor;
    private final AtomicInteger sequence = new AtomicInteger(700_000);

    @BeforeAll
    static void createFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("enrollmentTestPU");
    }

    @AfterAll
    static void closeFactory() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    @BeforeEach
    void setUp() {
        entityManager = entityManagerFactory.createEntityManager();
        report = new Ex6EnrollmentReport(entityManager);
        entityManager.getTransaction().begin();
        professor = new Professor("P0001", "Ada", "Lovelace",
                Email.of("ada.lovelace@unicam.it"), AcademicTitle.FULL_PROFESSOR, "Computer Science");
        entityManager.persist(professor);
    }

    @AfterEach
    void tearDown() {
        if (entityManager.getTransaction().isActive()) {
            entityManager.getTransaction().rollback();
        }
        entityManager.close();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private Course aCourse(String code) {
        Course course = new Course(code, "Course " + code, 6, 100,
                Semester.FALL, 2026, professor,
                AT.minusSeconds(86_400), AT.plusSeconds(86_400));
        entityManager.persist(course);
        return course;
    }

    private Student aStudent() {
        String number = String.valueOf(sequence.incrementAndGet());
        Student student = new Student(number, "Test", "Student",
                Email.of(number + "@studenti.unicam.it"),
                LocalDate.of(2004, 3, 14), 2026);
        entityManager.persist(student);
        return student;
    }

    /** Enrols {@code howMany} fresh students on {@code course}. */
    private void enrol(Course course, int howMany) {
        for (int i = 0; i < howMany; i++) {
            entityManager.persist(Enrollment.create(aStudent(), course, AT));
        }
    }

    /** Enrols one fresh student and immediately withdraws them. */
    private void enrolThenWithdraw(Course course) {
        Enrollment enrollment = Enrollment.create(aStudent(), course, AT);
        enrollment.withdraw(AT.plusSeconds(60));
        entityManager.persist(enrollment);
    }

    /** Everything must be on the database before an aggregate query can see it. */
    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    // ------------------------------------------------------------------
    // the specification
    // ------------------------------------------------------------------

    @Test
    @DisplayName("returns [code, count] for every course at or above the threshold")
    void countsPerCourse() {
        enrol(aCourse("CS101"), 3);
        enrol(aCourse("CS102"), 2);
        flush();

        List<Object[]> rows = report.popularCourses(2);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[0]).isEqualTo("CS101");
        assertThat(rows.get(0)[1]).isEqualTo(3L);
        assertThat(rows.get(1)[0]).isEqualTo("CS102");
        assertThat(rows.get(1)[1]).isEqualTo(2L);
    }

    @Test
    @DisplayName("the count is a Long, because that is what COUNT returns in JPQL")
    void countIsALong() {
        enrol(aCourse("CS101"), 1);
        flush();

        assertThat(report.popularCourses(1).get(0)[1])
                .isInstanceOf(Long.class)
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("the threshold is inclusive: a course with exactly n qualifies")
    void thresholdIsInclusive() {
        enrol(aCourse("CS101"), 2);
        flush();

        assertThat(report.popularCourses(2)).hasSize(1);
        assertThat(report.popularCourses(3)).isEmpty();
    }

    @Test
    @DisplayName("courses below the threshold are dropped by HAVING, not by a Java filter")
    void belowThresholdDropped() {
        enrol(aCourse("CS101"), 5);
        enrol(aCourse("CS102"), 1);
        flush();

        assertThat(report.popularCourses(2))
                .extracting(row -> row[0])
                .containsExactly("CS101");
    }

    @Test
    @DisplayName("withdrawn enrollments are excluded before grouping")
    void withdrawnExcluded() {
        Course course = aCourse("CS101");
        enrol(course, 2);
        enrolThenWithdraw(course);
        enrolThenWithdraw(course);
        flush();

        // four rows exist; only two of them count
        assertThat(report.popularCourses(1).get(0)[1]).isEqualTo(2L);
        assertThat(report.popularCourses(3)).isEmpty();
    }

    @Test
    @DisplayName("a course with no qualifying enrollments does not appear as a zero")
    void emptyCourseAbsent() {
        enrol(aCourse("CS101"), 2);
        aCourse("CS999");                 // no enrollments at all
        Course withdrawnOnly = aCourse("CS998");
        enrolThenWithdraw(withdrawnOnly); // only withdrawn ones
        flush();

        assertThat(report.popularCourses(0))
                .extracting(row -> row[0])
                .containsExactly("CS101")
                .doesNotContain("CS999", "CS998");
    }

    @Test
    @DisplayName("ordered by count descending, then code ascending")
    void ordering() {
        enrol(aCourse("CS300"), 1);
        enrol(aCourse("CS100"), 3);
        enrol(aCourse("CS200"), 1);
        enrol(aCourse("CS050"), 3);
        flush();

        assertThat(report.popularCourses(1))
                .extracting(row -> row[0])
                .containsExactly("CS050", "CS100", "CS200", "CS300");
    }

    @Test
    @DisplayName("returns an empty list, not null, when nothing qualifies")
    void emptyNotNull() {
        enrol(aCourse("CS101"), 1);
        flush();

        assertThat(report.popularCourses(99)).isNotNull().isEmpty();
    }
}
