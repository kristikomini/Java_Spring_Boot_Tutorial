package it.unicam.cs.enrollment.spring.reporting;

import it.unicam.cs.enrollment.spring.domain.AcademicTitle;
import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.Email;
import it.unicam.cs.enrollment.spring.domain.Enrollment;
import it.unicam.cs.enrollment.spring.domain.Professor;
import it.unicam.cs.enrollment.spring.domain.Semester;
import it.unicam.cs.enrollment.spring.domain.Student;
import it.unicam.cs.enrollment.spring.reporting.dto.DepartmentRankRow;
import it.unicam.cs.enrollment.spring.reporting.dto.FunnelRow;
import it.unicam.cs.enrollment.spring.reporting.dto.YearOverYearRow;
import it.unicam.cs.enrollment.spring.repository.CourseRepository;
import it.unicam.cs.enrollment.spring.repository.EnrollmentRepository;
import it.unicam.cs.enrollment.spring.repository.ProfessorRepository;
import it.unicam.cs.enrollment.spring.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 * THE ANALYTICS SQL, ACTUALLY EXECUTED
 * ============================================================================
 * These queries are native SQL, which means the ORM validates nothing about
 * them: a typo, a wrong alias, a window clause the database rejects - all of it
 * is a runtime failure on the first call. There is no such thing as "it
 * compiles" for the contents of ReportingRepository.
 *
 * <p>So every query in that file is executed here against a real (if in-memory)
 * database, and the numbers are asserted. This is the only thing standing
 * between the reporting endpoints and a 500 in production.
 *
 * <p>THE H2 CAVEAT APPLIES, and applies harder than usual. These queries were
 * written in the subset PostgreSQL and H2 agree on precisely so that this test
 * can exist - which is a real constraint on the SQL, and a trade worth naming:
 * FILTER (WHERE ...) would be cleaner than SUM(CASE WHEN ...) and is
 * PostgreSQL-only, so it is not used. Portable SQL is testable SQL, and where
 * the two conflict the tests win in this codebase.
 *
 * <p>The fixture is deliberately asymmetric - a tie in one department, a course
 * with nobody on it, a course present in one year and absent from another -
 * because every interesting bug in a reporting query is at a boundary that
 * uniform test data never reaches.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Window functions, CTEs and the materialised report")
class ReportingTest {

    @Autowired
    private ReportingRepository reportingRepository;

    @Autowired
    private CourseStatisticsRepository statisticsRepository;

    @Autowired
    private StatisticsRefreshService refreshService;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private ProfessorRepository professorRepository;

    @Autowired
    private EnrollmentRepository enrollmentRepository;

    @BeforeEach
    void seed() {
        statisticsRepository.deleteAllInBatch();
        enrollmentRepository.deleteAllInBatch();
        courseRepository.deleteAllInBatch();
        studentRepository.deleteAllInBatch();
        professorRepository.deleteAllInBatch();

        Professor cs = professorRepository.saveAndFlush(new Professor("P0001",
                "Marco", "Bianchi", Email.of("marco.bianchi@unicam.it"),
                AcademicTitle.ASSOCIATE_PROFESSOR, "Computer Science"));
        Professor maths = professorRepository.saveAndFlush(new Professor("P0002",
                "Anna", "Verdi", Email.of("anna.verdi@unicam.it"),
                AcademicTitle.FULL_PROFESSOR, "Mathematics"));

        // Computer Science: 3 enrollments, 2 enrollments, 2 enrollments (a TIE),
        // and one course with nobody at all.
        Course big = course("CS300", 2026, 10, cs);
        Course midA = course("CS201", 2026, 10, cs);
        Course midB = course("CS202", 2026, 10, cs);
        Course empty = course("CS999", 2026, 10, cs);
        Course mathsCourse = course("MA100", 2026, 50, maths);

        // The same code in the PREVIOUS year, so LAG has something to find.
        Course bigLastYear = course("CS300", 2025, 10, cs);

        courseRepository.saveAllAndFlush(
                List.of(big, midA, midB, empty, mathsCourse, bigLastYear));

        // 3 on CS300/2026: one completed, one failed, one withdrawn.
        Enrollment a = enroll("S0000001", big);
        Enrollment b = enroll("S0000002", big);
        Enrollment c = enroll("S0000003", big);
        a.recordPass(30, true, Instant.now());
        b.recordFailure(Instant.now());
        c.withdraw(Instant.now());

        // THE SAVES ARE NOT OPTIONAL, and forgetting them is fieldbook chapter
        // 09 catching this very test out.
        //
        // saveAndFlush() in enroll() commits its own transaction and returns.
        // The entity that comes back is DETACHED - outside any persistence
        // context - so the three state changes above are made to a plain Java
        // object that Hibernate is no longer watching. No dirty checking, no
        // UPDATE, and every enrollment stays ACTIVE in the database.
        //
        // The first version of this test omitted these three lines, and the
        // symptom was a reporting assertion failing by exactly the number of
        // rows that should have changed state - which reads like a broken
        // window function rather than a detached entity. Inside a
        // @Transactional method the mutations alone would have been enough,
        // which is precisely why the distinction is worth feeling once.
        enrollmentRepository.saveAndFlush(a);
        enrollmentRepository.saveAndFlush(b);
        enrollmentRepository.saveAndFlush(c);

        // 2 each on CS201 and CS202 - the tie.
        enroll("S0000004", midA);
        enroll("S0000005", midA);
        enroll("S0000006", midB);
        enroll("S0000007", midB);

        // 1 on maths.
        enroll("S0000008", mathsCourse);

        // 1 on CS300 in 2025, so 2026 shows a delta of +2.
        enroll("S0000009", bigLastYear);

        enrollmentRepository.flush();
    }

    // ------------------------------------------------------------------
    // Window function: SUM(COUNT(*)) OVER ()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the funnel percentages come from a window over the grouped counts")
    void funnelPercentagesUseAWindowFunction() {
        List<FunnelRow> funnel = reportingRepository.enrollmentFunnel(2026);

        Map<String, FunnelRow> byStatus = funnel.stream()
                .collect(Collectors.toMap(FunnelRow::getStatus, Function.identity()));

        // 8 enrollments in 2026: 5 ACTIVE, 1 COMPLETED, 1 FAILED, 1 WITHDRAWN.
        assertThat(byStatus.get("ACTIVE").getCount()).isEqualTo(5);
        assertThat(byStatus.get("COMPLETED").getCount()).isEqualTo(1);

        // 5/8 = 62.50. The assertion that proves SUM(COUNT(*)) OVER () computed
        // the grand total: if the window were wrong, every percentage would be
        // 100. And if 100.0 had been written as 100, INTEGER DIVISION would make
        // every one of them 0 - which is why the literal has a decimal point.
        assertThat(byStatus.get("ACTIVE").getPercentage())
                .isEqualByComparingTo(new BigDecimal("62.50"));
        assertThat(byStatus.get("COMPLETED").getPercentage())
                .isEqualByComparingTo(new BigDecimal("12.50"));

        // They add up to 100, which is the property a percentage column must
        // have and the one nobody checks.
        BigDecimal total = funnel.stream()
                .map(FunnelRow::getPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    // ------------------------------------------------------------------
    // Window functions: RANK vs DENSE_RANK, PARTITION BY
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RANK leaves a gap after a tie and DENSE_RANK does not")
    void rankAndDenseRankDifferAfterATie() {
        Map<String, DepartmentRankRow> byCode =
                reportingRepository.rankCoursesWithinDepartment(2026).stream()
                        .collect(Collectors.toMap(DepartmentRankRow::getCourseCode,
                                Function.identity()));

        // Computer Science: CS300=3, CS201=2, CS202=2, CS999=0.
        assertThat(byCode.get("CS300").getEnrollments()).isEqualTo(3);
        assertThat(byCode.get("CS300").getDepartmentRank()).isEqualTo(1);

        // The two tied courses share position 2 under both functions.
        assertThat(byCode.get("CS201").getDepartmentRank()).isEqualTo(2);
        assertThat(byCode.get("CS202").getDepartmentRank()).isEqualTo(2);

        // THE DIFFERENCE, in one pair of assertions. RANK skips 3 because two
        // courses occupy position 2; DENSE_RANK does not.
        assertThat(byCode.get("CS999").getDepartmentRank()).isEqualTo(4);
        assertThat(byCode.get("CS999").getDepartmentDenseRank()).isEqualTo(3);
    }

    @Test
    @DisplayName("PARTITION BY restarts the ranking in each department")
    void partitionByRestartsTheRanking() {
        Map<String, DepartmentRankRow> byCode =
                reportingRepository.rankCoursesWithinDepartment(2026).stream()
                        .collect(Collectors.toMap(DepartmentRankRow::getCourseCode,
                                Function.identity()));

        // MA100 has ONE enrollment - fewer than three courses in Computer
        // Science - and is still rank 1, because it is first in Mathematics.
        // That is what PARTITION BY means, and it is the assertion that fails if
        // somebody removes it.
        assertThat(byCode.get("MA100").getEnrollments()).isEqualTo(1);
        assertThat(byCode.get("MA100").getDepartmentRank()).isEqualTo(1);
        assertThat(byCode.get("MA100").getDepartment()).isEqualTo("Mathematics");
    }

    @Test
    @DisplayName("a course with no enrollments counts 0, not 1")
    void leftJoinCountsZeroNotOne() {
        Map<String, DepartmentRankRow> byCode =
                reportingRepository.rankCoursesWithinDepartment(2026).stream()
                        .collect(Collectors.toMap(DepartmentRankRow::getCourseCode,
                                Function.identity()));

        // THE CLASSIC LEFT JOIN BUG. With COUNT(*) instead of COUNT(e.id), the
        // single all-NULL row a LEFT JOIN produces for an empty course counts as
        // 1, and every empty course in the report claims one student.
        assertThat(byCode.get("CS999").getEnrollments()).isZero();
    }

    // ------------------------------------------------------------------
    // CTE + LAG
    // ------------------------------------------------------------------

    @Test
    @DisplayName("LAG finds the previous year, and reports null for the first one")
    void lagComparesToThePreviousYear() {
        List<YearOverYearRow> rows = reportingRepository.yearOverYear().stream()
                .filter(r -> r.getCourseCode().equals("CS300"))
                .toList();

        assertThat(rows).hasSize(2);

        YearOverYearRow y2025 = rows.get(0);
        YearOverYearRow y2026 = rows.get(1);

        assertThat(y2025.getAcademicYear()).isEqualTo(2025);
        assertThat(y2025.getEnrollments()).isEqualTo(1);
        // NULL, not zero. The first row of a partition has nothing before it,
        // and a report that cannot tell "no previous year" from "no enrollments
        // last year" will eventually mislead somebody. This is why the
        // projection uses Long and not long.
        assertThat(y2025.getPreviousYear()).isNull();
        assertThat(y2025.getDelta()).isNull();

        assertThat(y2026.getAcademicYear()).isEqualTo(2026);
        assertThat(y2026.getEnrollments()).isEqualTo(3);
        assertThat(y2026.getPreviousYear()).isEqualTo(1L);
        assertThat(y2026.getDelta()).isEqualTo(2L);
    }

    // ------------------------------------------------------------------
    // The materialised report and the job that writes it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the refresh job writes one row per course, with computedAt")
    void refreshWritesEveryCourse() {
        assertThat(statisticsRepository.count()).isZero();

        int written = refreshService.refreshAll();

        // Six courses in the fixture, including the empty one and last year.
        assertThat(written).isEqualTo(6);
        assertThat(statisticsRepository.count()).isEqualTo(6);

        CourseStatistics cs300 = statisticsRepository.findAll().stream()
                .filter(s -> s.getCourseCode().equals("CS300") && s.getAcademicYear() == 2026)
                .findFirst().orElseThrow();

        assertThat(cs300.getTotalEnrollments()).isEqualTo(3);
        assertThat(cs300.getCompletedCount()).isEqualTo(1);
        assertThat(cs300.getFailedCount()).isEqualTo(1);
        assertThat(cs300.getWithdrawnCount()).isEqualTo(1);
        assertThat(cs300.getActiveCount()).isZero();

        // Denormalised from the courses and professors tables, so a dashboard
        // reads this row without joining back - see V6__course_statistics.sql.
        assertThat(cs300.getDepartment()).isEqualTo("Computer Science");
        assertThat(cs300.getCourseTitle()).isEqualTo("Course CS300");

        // Without this a caller cannot tell live data from the output of a job
        // that has been failing since Friday.
        assertThat(cs300.getComputedAt()).isNotNull();
    }

    @Test
    @DisplayName("the pass rate divides completions by completions plus failures")
    void passRateIsComputedCorrectly() {
        refreshService.refreshAll();

        CourseStatistics cs300 = statisticsRepository.findAll().stream()
                .filter(s -> s.getCourseCode().equals("CS300") && s.getAcademicYear() == 2026)
                .findFirst().orElseThrow();

        // 1 completed, 1 failed, 1 withdrawn. The rate is 1/(1+1) = 50%, NOT
        // 1/3 = 33%: a student who withdrew never sat the exam and does not
        // belong in the denominator. That is a business rule living in a SQL
        // expression, which is exactly the kind of thing that needs a test
        // beside it saying what it means.
        assertThat(cs300.getPassRate()).isEqualByComparingTo(new BigDecimal("50.00"));

        // One grade of 30.
        assertThat(cs300.getAverageGrade()).isEqualByComparingTo(new BigDecimal("30.00"));

        // Seats held: ACTIVE + FAILED = 1, out of capacity 10.
        assertThat(cs300.getFillRate()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("a course nobody finished has a null pass rate, not zero, and does not divide by zero")
    void nullifGuardsTheDivision() {
        refreshService.refreshAll();

        CourseStatistics empty = statisticsRepository.findAll().stream()
                .filter(s -> s.getCourseCode().equals("CS999"))
                .findFirst().orElseThrow();

        // Without NULLIF this query divides by zero. In PostgreSQL that is an
        // ERROR that fails the entire job - not a NaN, not a null - so one empty
        // course would take down the whole nightly refresh.
        assertThat(empty.getPassRate()).isNull();
        assertThat(empty.getAverageGrade()).isNull();
        // Zero enrollments over capacity 10 is a genuine 0%, which IS known.
        assertThat(empty.getFillRate()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("the refresh is idempotent - running it twice leaves the same rows")
    void refreshIsIdempotent() {
        refreshService.refreshAll();
        long after1 = statisticsRepository.count();

        refreshService.refreshAll();
        long after2 = statisticsRepository.count();

        // The delete-then-insert strategy. Without the delete, the second run
        // would either duplicate every row or leave stale rows for courses that
        // no longer exist. Idempotence is what makes a retry after a timeout
        // safe, which is why it is worth a test of its own.
        assertThat(after2).isEqualTo(after1).isEqualTo(6);
    }

    @Test
    @DisplayName("under-subscribed courses come back emptiest first")
    void underSubscribedIsSortedAscending() {
        refreshService.refreshAll();

        List<CourseStatistics> worst = statisticsRepository.findUnderSubscribed(2026);

        assertThat(worst).isNotEmpty();
        // The empty course is the worst, and the report a head of department
        // opens should put it first rather than make them scroll.
        assertThat(worst.get(0).getCourseCode()).isEqualTo("CS999");
        assertThat(worst).isSortedAccordingTo(
                java.util.Comparator.comparing(CourseStatistics::getFillRate));
    }

    // ------------------------------------------------------------------

    private Course course(String code, int year, int capacity, Professor professor) {
        return new Course(code, "Course " + code, 9, capacity, Semester.FALL, year, professor,
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2035-01-01T00:00:00Z"));
    }

    private Enrollment enroll(String studentNumber, Course course) {
        Student student = studentRepository.saveAndFlush(new Student(studentNumber,
                "Test", "Student", Email.of(studentNumber.toLowerCase() + "@studenti.unicam.it"),
                LocalDate.of(2004, 1, 1), 2025));
        return enrollmentRepository.saveAndFlush(
                Enrollment.create(student, course, Instant.now()));
    }
}
