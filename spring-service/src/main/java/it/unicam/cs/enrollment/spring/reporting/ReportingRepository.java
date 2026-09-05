package it.unicam.cs.enrollment.spring.reporting;

import it.unicam.cs.enrollment.spring.domain.Enrollment;
import it.unicam.cs.enrollment.spring.reporting.dto.DepartmentRankRow;
import it.unicam.cs.enrollment.spring.reporting.dto.FunnelRow;
import it.unicam.cs.enrollment.spring.reporting.dto.YearOverYearRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ============================================================================
 * THE SQL A "DATA & ANALYTICS" ADVERT IS ACTUALLY ASKING ABOUT
 * ============================================================================
 * Fieldbook chapter 07 stops at GROUP BY. Every query in this file goes past
 * it, and the three constructs below are the ones that come up:
 *
 * <p>WINDOW FUNCTIONS - {@code RANK() OVER (PARTITION BY ... ORDER BY ...)}.
 * The one-sentence definition worth memorising: a window function computes
 * across a set of rows related to the current row, WITHOUT collapsing them the
 * way GROUP BY does. Every input row still comes out, with an extra column.
 * That is the whole difference, and it is the answer to the interview question.
 *
 * <p>Before window functions, ranking within a group meant a self-join or a
 * correlated subquery per row. If you have ever written "the top 3 per
 * category" with a subquery, this is what replaces it.
 *
 * <p>CTEs - {@code WITH name AS (...)}. A named subquery, readable top to
 * bottom rather than inside out. In PostgreSQL since version 12 they are
 * inlined by the planner rather than materialised, so they are free; before
 * that they were an optimisation fence, which is why older advice warns against
 * them.
 *
 * <p>AGGREGATES INSIDE WINDOWS - {@code SUM(COUNT(*)) OVER ()}. The one that
 * looks illegal and is not. It computes a total across all the groups the
 * GROUP BY produced, which is how a percentage-of-total column is written
 * without running the query twice.
 *
 * <p>WHY THESE ARE NATIVE QUERIES AND NOT JPQL. JPQL has no window functions -
 * Hibernate 6 added partial support, and it is neither complete nor portable.
 * This is the honest boundary of an ORM: it is excellent at the object graph
 * and has nothing to offer analytical SQL. Reaching for {@code nativeQuery}
 * here is not a failure, it is using the right tool. The cost is that the SQL
 * is now dialect-specific and must be tested against a real database - which is
 * exactly why these queries are written in the subset PostgreSQL and H2 agree
 * on, so the slice tests can exercise them.
 */
@Repository
public interface ReportingRepository extends JpaRepository<Enrollment, Long> {

    /**
     * THE ENROLLMENT FUNNEL: how many enrollments are in each state, and what
     * share of the total each state represents.
     *
     * <p>{@code SUM(COUNT(*)) OVER ()} is the line to understand. COUNT(*) is
     * evaluated per group by the GROUP BY; SUM(...) OVER () then adds those
     * group counts together across the whole result. An empty OVER () means
     * "the window is every row", so it is the grand total.
     *
     * <p>The alternative without a window function is to run the same query
     * twice, or to compute the total in Java and divide there. Both work; both
     * are slower and one of them is two queries that can disagree.
     *
     * <p>{@code 100.0 *} rather than {@code 100 *} forces the division to
     * happen in floating point. With integer operands, {@code count / total} is
     * integer division and every percentage below 100% comes out as 0. It is a
     * genuinely common bug and it is silent.
     */
    @Query(value = """
            SELECT e.status                                              AS status,
                   COUNT(*)                                              AS count,
                   ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2)    AS percentage
            FROM enrollments e
            JOIN courses c ON c.id = e.course_id
            WHERE c.academic_year = :year
            GROUP BY e.status
            ORDER BY count DESC
            """, nativeQuery = true)
    List<FunnelRow> enrollmentFunnel(@Param("year") int year);

    /**
     * COURSES RANKED WITHIN THEIR DEPARTMENT.
     *
     * <p>Two window functions over the same partition:
     *
     * <p>{@code RANK()} leaves gaps after a tie - 1, 2, 2, 4. {@code DENSE_RANK()}
     * does not - 1, 2, 2, 3. {@code ROW_NUMBER()} never ties at all and picks an
     * arbitrary winner. Choosing between them is a product decision ("do two
     * courses tied for second mean nobody is third?") and getting asked to
     * explain the difference is close to guaranteed in any interview that
     * mentions SQL seriously.
     *
     * <p>Note the window function ranks by {@code COUNT(e.id)} - an aggregate.
     * That is legal because window functions are evaluated AFTER GROUP BY, which
     * is the same clause-ordering fact chapter 07 uses to explain why WHERE
     * cannot see a SELECT alias. The order is
     * FROM, JOIN, WHERE, GROUP BY, HAVING, WINDOW, SELECT, ORDER BY, LIMIT.
     *
     * <p>{@code COUNT(e.id)} and not {@code COUNT(*)}: with a LEFT JOIN, a
     * course with no enrollments produces one row with NULLs, and COUNT(*)
     * counts that row as 1. COUNT of a nullable column skips nulls and returns
     * 0. Every empty course reporting one student is the classic version of
     * this bug.
     */
    @Query(value = """
            SELECT c.code                                                AS courseCode,
                   c.title                                               AS courseTitle,
                   p.department                                          AS department,
                   COUNT(e.id)                                           AS enrollments,
                   c.capacity                                            AS capacity,
                   RANK()       OVER (PARTITION BY p.department
                                      ORDER BY COUNT(e.id) DESC)         AS departmentRank,
                   DENSE_RANK() OVER (PARTITION BY p.department
                                      ORDER BY COUNT(e.id) DESC)         AS departmentDenseRank
            FROM courses c
            JOIN professors p  ON p.id = c.professor_id
            LEFT JOIN enrollments e ON e.course_id = c.id
            WHERE c.academic_year = :year
            GROUP BY c.id, c.code, c.title, c.capacity, p.department
            ORDER BY p.department ASC, enrollments DESC
            """, nativeQuery = true)
    List<DepartmentRankRow> rankCoursesWithinDepartment(@Param("year") int year);

    /**
     * YEAR ON YEAR, with {@code LAG}.
     *
     * <p>{@code LAG(x) OVER (PARTITION BY code ORDER BY year)} gives you the
     * value of x from the PREVIOUS row in that partition. It is how every
     * "compared to last period" column in every dashboard is built, and doing it
     * without a window function means joining the table to itself on
     * {@code year = year - 1} - which quietly drops any course that skipped a
     * year.
     *
     * <p>{@code LAG(x, 1, 0)} would default the first row to 0 instead of NULL.
     * That is left as NULL here deliberately: a course in its first year has no
     * previous year, and reporting a change of "+30 from 0" would be a lie about
     * data that does not exist. NULL means "not applicable", and a report that
     * cannot distinguish that from zero is a report that will eventually mislead
     * somebody.
     *
     * <p>{@code LEAD} is the same function looking forwards.
     */
    @Query(value = """
            WITH yearly AS (
                SELECT c.code           AS code,
                       c.academic_year  AS academic_year,
                       COUNT(e.id)      AS enrollments
                FROM courses c
                LEFT JOIN enrollments e ON e.course_id = c.id
                GROUP BY c.code, c.academic_year
            )
            SELECT code                                                  AS courseCode,
                   academic_year                                         AS academicYear,
                   enrollments                                           AS enrollments,
                   LAG(enrollments) OVER (PARTITION BY code
                                          ORDER BY academic_year)        AS previousYear,
                   enrollments - LAG(enrollments) OVER (PARTITION BY code
                                          ORDER BY academic_year)        AS delta
            FROM yearly
            ORDER BY code ASC, academic_year ASC
            """, nativeQuery = true)
    List<YearOverYearRow> yearOverYear();

    /**
     * The aggregate the refresh job materialises into course_statistics.
     *
     * <p>Everything here is computed BY THE DATABASE and returned as one row per
     * course. The alternative - load every enrollment and group in Java - is
     * chapter 05 warning about {@code groupingBy} over entities, at full scale:
     * for a faculty with fifty thousand enrollments it is fifty thousand objects
     * to produce two hundred rows.
     *
     * <p>{@code FILTER (WHERE ...)} would be the elegant PostgreSQL way to write
     * these conditional counts. {@code SUM(CASE WHEN ... THEN 1 ELSE 0 END)} is
     * used instead because it is standard SQL and runs unchanged on H2, so the
     * slice tests can execute it. That is a real trade this codebase makes more
     * than once: portable SQL is testable SQL.
     *
     * <p>{@code NULLIF(x, 0)} guards the division. Without it, a course whose
     * students have all withdrawn divides by zero - which in PostgreSQL is an
     * ERROR that fails the whole job, not a NaN. NULLIF turns the zero into NULL
     * and the division into NULL, which is the correct answer: the pass rate of
     * a course nobody finished is unknown, not zero.
     */
    @Query(value = """
            SELECT c.id                                                  AS courseId,
                   c.code                                                AS courseCode,
                   c.title                                               AS courseTitle,
                   c.academic_year                                       AS academicYear,
                   c.semester                                            AS semester,
                   p.department                                          AS department,
                   c.capacity                                            AS capacity,
                   COUNT(e.id)                                           AS totalEnrollments,
                   SUM(CASE WHEN e.status = 'ACTIVE'    THEN 1 ELSE 0 END) AS activeCount,
                   SUM(CASE WHEN e.status = 'COMPLETED' THEN 1 ELSE 0 END) AS completedCount,
                   SUM(CASE WHEN e.status = 'FAILED'    THEN 1 ELSE 0 END) AS failedCount,
                   SUM(CASE WHEN e.status = 'WITHDRAWN' THEN 1 ELSE 0 END) AS withdrawnCount,
                   ROUND(100.0 * SUM(CASE WHEN e.status = 'COMPLETED' THEN 1 ELSE 0 END)
                         / NULLIF(SUM(CASE WHEN e.status IN ('COMPLETED','FAILED')
                                           THEN 1 ELSE 0 END), 0), 2)   AS passRate,
                   ROUND(AVG(CAST(e.grade AS DECIMAL(4,2))), 2)          AS averageGrade,
                   ROUND(100.0 * SUM(CASE WHEN e.status IN ('ACTIVE','FAILED')
                                          THEN 1 ELSE 0 END)
                         / NULLIF(c.capacity, 0), 2)                     AS fillRate
            FROM courses c
            JOIN professors p ON p.id = c.professor_id
            LEFT JOIN enrollments e ON e.course_id = c.id
            GROUP BY c.id, c.code, c.title, c.academic_year, c.semester,
                     p.department, c.capacity
            ORDER BY c.id
            """, nativeQuery = true)
    List<CourseStatisticsRow> computeAllCourseStatistics();
}
