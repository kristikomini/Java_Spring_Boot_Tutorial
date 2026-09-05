package it.unicam.cs.enrollment.spring.repository;

import it.unicam.cs.enrollment.spring.domain.AcademicTitle;
import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.Email;
import it.unicam.cs.enrollment.spring.domain.Enrollment;
import it.unicam.cs.enrollment.spring.domain.EnrollmentStatus;
import it.unicam.cs.enrollment.spring.domain.Professor;
import it.unicam.cs.enrollment.spring.domain.Semester;
import it.unicam.cs.enrollment.spring.domain.Student;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 * A SLICE TEST - JPA AND NOTHING ELSE
 * ============================================================================
 * {@code @DataJpaTest} starts a Spring context containing the entities, the
 * repositories, a transaction manager and a datasource - and NOT the web layer,
 * the controllers, the mappers or the services. A slice, which starts in about a
 * second where {@code @SpringBootTest} takes several.
 *
 * <p>Three behaviours of this annotation are worth knowing, because all three
 * surprise people:
 *
 * <p>1. IT REPLACES THE DATASOURCE. By default it swaps whatever you configured
 * for an in-memory one, which is why the PostgreSQL URL in application.yml is
 * not used here. That substitution is also why the -test profile has to set
 * ddl-auto to create-drop.
 *
 * <p>2. EVERY TEST IS TRANSACTIONAL AND ROLLS BACK. No cleanup code, no
 * ordering dependencies between tests. It also means the persistence context
 * lives for the whole test method, which brings us to the third point.
 *
 * <p>3. THE PERSISTENCE CONTEXT WILL LIE TO YOU if you let it. After saving an
 * entity, a findById returns the SAME OBJECT from the first-level cache without
 * going to the database - so a broken mapping still passes. The fix is
 * {@code entityManager.flush()} then {@code clear()}, which forces the SQL out
 * and empties the cache, so the next query genuinely reads rows. Every test
 * below does it, and skipping it is the most common way a repository test
 * proves nothing. Fieldbook chapter 09 is the long version.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("CourseRepository - against a real (if in-memory) database")
class CourseRepositoryTest {

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private EnrollmentRepository enrollmentRepository;

    @Autowired
    private StudentRepository studentRepository;

    /**
     * The raw EntityManager, injected alongside the repositories.
     *
     * <p>Needed only for flush/clear. Spring Boot also offers TestEntityManager,
     * a thin wrapper with a persistAndFlush helper; the real thing is used here
     * because it is the same API the Jakarta EE repository tests use, which keeps
     * the two comparable.
     */
    @Autowired
    private EntityManager entityManager;

    private Professor professor;

    @BeforeEach
    void setUp() {
        professor = new Professor("P0001", "Marco", "Bianchi",
                Email.of("marco.bianchi@unicam.it"),
                AcademicTitle.ASSOCIATE_PROFESSOR, "Computer Science");
        entityManager.persist(professor);
    }

    @Test
    @DisplayName("derived query: findByCodeAndAcademicYear resolves from the method name")
    void derivedQueryFindsByCodeAndYear() {
        courseRepository.save(course("CS101", 2026, 30));
        flushAndClear();

        Optional<Course> found = courseRepository.findByCodeAndAcademicYear("CS101", 2026);

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Course CS101");

        // Same code, different year: the unique constraint is on the PAIR, so
        // this must miss. A derived query that ignored academicYear would still
        // pass the assertion above and fail this one.
        assertThat(courseRepository.findByCodeAndAcademicYear("CS101", 2025)).isEmpty();
    }

    @Test
    @DisplayName("derived query: existsByCodeAndAcademicYear")
    void derivedExistsQuery() {
        courseRepository.save(course("CS102", 2026, 30));
        flushAndClear();

        assertThat(courseRepository.existsByCodeAndAcademicYear("CS102", 2026)).isTrue();
        assertThat(courseRepository.existsByCodeAndAcademicYear("CS999", 2026)).isFalse();
    }

    @Test
    @DisplayName("@Query with JOIN FETCH returns only courses inside the window")
    void findsOpenCoursesOnly() {
        Instant now = Instant.parse("2026-09-05T10:00:00Z");

        courseRepository.save(course("OPEN01", 2026, 30,
                now.minusSeconds(86400), now.plusSeconds(86400)));
        courseRepository.save(course("SHUT01", 2026, 30,
                now.minusSeconds(200000), now.minusSeconds(100000)));
        courseRepository.save(course("SOON01", 2026, 30,
                now.plusSeconds(100000), now.plusSeconds(200000)));
        flushAndClear();

        List<Course> open = courseRepository.findOpenForEnrollment(now);

        assertThat(open).extracting(Course::getCode).containsExactly("OPEN01");

        // The point of the JOIN FETCH: the association is already populated, so
        // this does not fire a second query. The context was cleared above, so
        // if the fetch join were missing this would be a lazy load - which still
        // works INSIDE the test transaction, and would fail in a real request.
        // That asymmetry is why chapter 08 insists on running the experiment
        // rather than reasoning about it.
        assertThat(open.get(0).getProfessor().fullName()).isEqualTo("Marco Bianchi");
    }

    @Test
    @DisplayName("LEFT JOIN FETCH keeps a course that has no prerequisites")
    void leftJoinFetchKeepsCoursesWithoutPrerequisites() {
        Course lonely = courseRepository.save(course("SOLO01", 2026, 30));
        flushAndClear();

        // With an INNER join here this would return empty - the bug the word
        // LEFT prevents, and one that only shows up for the rows you forgot to
        // create in the happy-path test.
        Optional<Course> found = courseRepository.findByIdWithPrerequisites(lonely.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getPrerequisites()).isEmpty();
    }

    @Test
    @DisplayName("LEFT JOIN FETCH loads prerequisites without duplicating the course")
    void fetchesPrerequisites() {
        Course intro = courseRepository.save(course("CS100", 2026, 100));
        Course maths = courseRepository.save(course("MA100", 2026, 100));
        Course advanced = course("CS300", 2026, 30);
        advanced.addPrerequisite(intro);
        advanced.addPrerequisite(maths);
        courseRepository.save(advanced);
        flushAndClear();

        Optional<Course> found = courseRepository.findByIdWithPrerequisites(advanced.getId());

        assertThat(found).isPresent();
        // Two prerequisites, one Course. Without DISTINCT the fetch join would
        // multiply the root rows and this is where you would see it.
        assertThat(found.get().getPrerequisites())
                .extracting(Course::getCode)
                .containsExactlyInAnyOrder("CS100", "MA100");
    }

    @Test
    @DisplayName("pagination reports the total across all pages, not the page size")
    void paginatesWithCorrectTotal() {
        for (int i = 1; i <= 7; i++) {
            courseRepository.save(course(String.format("PG%03d", i), 2026, 30));
        }
        flushAndClear();

        Page<Course> firstPage = courseRepository.findByYearAndOptionalSemester(
                2026, null, PageRequest.of(0, 3, Sort.by("code").ascending()));

        assertThat(firstPage.getContent()).hasSize(3);
        // The assertion that catches the classic bug: a count query whose WHERE
        // clause has drifted from the content query reports the wrong total, and
        // every client paginating on it breaks.
        assertThat(firstPage.getTotalElements()).isEqualTo(7);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(firstPage.isFirst()).isTrue();
        assertThat(firstPage.hasNext()).isTrue();

        Page<Course> lastPage = courseRepository.findByYearAndOptionalSemester(
                2026, null, PageRequest.of(2, 3, Sort.by("code").ascending()));
        assertThat(lastPage.getContent()).hasSize(1);
        assertThat(lastPage.isLast()).isTrue();
        assertThat(lastPage.hasNext()).isFalse();
    }

    @Test
    @DisplayName("the optional semester filter applies when given and is ignored when null")
    void optionalSemesterFilter() {
        courseRepository.save(course("FA0001", 2026, 30, Semester.FALL));
        courseRepository.save(course("SP0001", 2026, 30, Semester.SPRING));
        flushAndClear();

        assertThat(courseRepository.findByYearAndOptionalSemester(
                2026, Semester.FALL, PageRequest.of(0, 10)).getContent())
                .extracting(Course::getCode).containsExactly("FA0001");

        assertThat(courseRepository.findByYearAndOptionalSemester(
                2026, null, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("countOccupiedSeats counts ACTIVE and FAILED, and ignores WITHDRAWN")
    void countsOnlyOccupyingStatuses() {
        Course course = courseRepository.save(course("SEAT01", 2026, 30));

        Enrollment active = enrollmentRepository.save(
                Enrollment.create(student("S0000001"), course, Instant.now()));
        Enrollment failed = enrollmentRepository.save(
                Enrollment.create(student("S0000002"), course, Instant.now()));
        Enrollment withdrawn = enrollmentRepository.save(
                Enrollment.create(student("S0000003"), course, Instant.now()));

        failed.recordFailure(Instant.now());
        withdrawn.withdraw(Instant.now());
        flushAndClear();

        long occupied = enrollmentRepository.countOccupiedSeats(course.getId(),
                List.of(EnrollmentStatus.ACTIVE, EnrollmentStatus.FAILED));

        // Three rows, two seats. This is the whole seat rule, executed as SQL
        // rather than mocked - which is what the unit test cannot do.
        assertThat(occupied).isEqualTo(2);
        assertThat(active.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
    }

    @Test
    @DisplayName("the grouped seat count answers for many courses in one query")
    void countsSeatsForManyCoursesAtOnce() {
        Course a = courseRepository.save(course("GRP001", 2026, 30));
        Course b = courseRepository.save(course("GRP002", 2026, 30));
        Course empty = courseRepository.save(course("GRP003", 2026, 30));

        enrollmentRepository.save(Enrollment.create(student("S0000010"), a, Instant.now()));
        enrollmentRepository.save(Enrollment.create(student("S0000011"), a, Instant.now()));
        enrollmentRepository.save(Enrollment.create(student("S0000012"), b, Instant.now()));
        flushAndClear();

        List<Object[]> rows = enrollmentRepository.countOccupiedSeatsByCourse(
                List.of(a.getId(), b.getId(), empty.getId()),
                List.of(EnrollmentStatus.ACTIVE, EnrollmentStatus.FAILED));

        // GROUP BY returns no row for a course with no enrollments - it does not
        // return zero. That is why CourseService uses getOrDefault(id, 0L), and
        // forgetting it is a NullPointerException on the emptiest course in the
        // catalogue.
        assertThat(rows).hasSize(2);
    }

    @Test
    @DisplayName("the unique constraint on (student, course) is real, not just a check in code")
    void uniqueConstraintExists() {
        Course course = courseRepository.save(course("UNQ001", 2026, 30));
        Student student = student("S0000020");
        enrollmentRepository.save(Enrollment.create(student, course, Instant.now()));
        flushAndClear();

        enrollmentRepository.save(Enrollment.create(student, course, Instant.now()));

        // The database refuses the second row. The service checks first and
        // returns a friendly 409; THIS is the guarantee underneath that check,
        // and it is the one that still holds when two servers race.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> entityManager.flush())
                .isInstanceOf(Exception.class);
    }

    // ------------------------------------------------------------------

    /**
     * Push pending SQL to the database, then empty the persistence context.
     *
     * <p>Without the clear, the next findById is answered from the first-level
     * cache and never touches the database - so the test would pass even if the
     * mapping were wrong. This one method is the difference between a repository
     * test and a very slow way of testing a HashMap.
     */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Student student(String number) {
        Student student = new Student(number, "Test", "Student",
                Email.of(number.toLowerCase() + "@studenti.unicam.it"),
                LocalDate.of(2004, 1, 1), 2025);
        return studentRepository.save(student);
    }

    private Course course(String code, int year, int capacity) {
        return course(code, year, capacity, Semester.FALL);
    }

    private Course course(String code, int year, int capacity, Semester semester) {
        return new Course(code, "Course " + code, 9, capacity, semester, year, professor,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"));
    }

    private Course course(String code, int year, int capacity, Instant opens, Instant closes) {
        return new Course(code, "Course " + code, 9, capacity, Semester.FALL, year, professor,
                opens, closes);
    }
}
