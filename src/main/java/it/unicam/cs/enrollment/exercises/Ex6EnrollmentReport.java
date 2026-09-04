package it.unicam.cs.enrollment.exercises;

import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Objects;

/**
 * EXERCISE 6 - The report query (JOIN, GROUP BY, HAVING)
 * =============================================================================
 * Difficulty: moderate. This is the SQL question asked more often than any Java
 * one, phrased as JPQL.
 *
 * <p>Run the tests for this exercise with:
 * <pre>mvn test -Pexercises -Dtest=Ex6EnrollmentReportTest</pre>
 *
 * <h2>What to do</h2>
 * Implement {@link #popularCourses(int)} so that it returns, for every course
 * with <em>at least</em> {@code minimumEnrollments} enrollments that are not
 * withdrawn, a row of {@code [courseCode, count]} - ordered by count descending,
 * then by course code ascending so the result is deterministic.
 *
 * <h2>What you are practising</h2>
 * <ul>
 *   <li><b>The aggregate pipeline.</b> Rows are produced, then {@code WHERE}
 *       filters rows, then {@code GROUP BY} collapses them, then {@code HAVING}
 *       filters the groups, then {@code ORDER BY} sorts what is left. Getting
 *       that order right is most of the question - and it is why the
 *       "not withdrawn" condition belongs in {@code WHERE} while the
 *       "at least n" condition belongs in {@code HAVING}.</li>
 *   <li><b>Projection instead of entities.</b> You do not want {@code Course}
 *       objects here; you want two columns. A JPQL query selecting more than one
 *       expression yields {@code Object[]} rows, which is why the return type
 *       below is what it is. In a real code base you would project into a DTO
 *       with a constructor expression - see the hint.</li>
 *   <li><b>Joins that do not fetch.</b> {@code JOIN c.enrollments e} here is a
 *       plain join used for aggregation. It is not a {@code JOIN FETCH}: nothing
 *       is being loaded into the persistence context, so none of the fetch-plan
 *       reasoning from the persistence chapters applies.</li>
 * </ul>
 *
 * <h2>Hints</h2>
 * <ul>
 *   <li>Start from {@code SELECT c.code, COUNT(e) FROM Course c JOIN c.enrollments e}.</li>
 *   <li>{@code WHERE e.status &lt;&gt; :withdrawn} with the enum bound as a
 *       parameter - never spelled into the string.</li>
 *   <li>{@code GROUP BY c.code} then {@code HAVING COUNT(e) &gt;= :minimum}.</li>
 *   <li>{@code ORDER BY COUNT(e) DESC, c.code ASC}.</li>
 *   <li>{@code COUNT} returns a {@code Long} in JPQL, not an {@code Integer}.
 *       The tests expect a {@code Long} in slot 1 and will tell you plainly if
 *       you cast it away.</li>
 *   <li>The tidy production form is a constructor expression -
 *       {@code SELECT new it.unicam...CourseCount(c.code, COUNT(e)) ...} - but
 *       this exercise deliberately keeps the raw {@code Object[]} so you see
 *       what the provider actually hands back.</li>
 * </ul>
 *
 * <h2>The trap worth meeting once</h2>
 * A course with <em>zero</em> non-withdrawn enrollments must not appear at all.
 * That happens for free with an inner {@code JOIN}. Reach for a {@code LEFT
 * JOIN} instead - a reasonable instinct if you are thinking "include every
 * course" - and every empty course arrives with a count of zero, which the
 * {@code HAVING} then has to remove. Both work; knowing which one you wrote and
 * why is the mark.
 *
 * <p>Compare your answer afterwards with the open-courses query in
 * {@code CourseRepository}, which solves the mirror-image problem: it needs the
 * courses with seats <em>left</em>, and therefore genuinely does need the
 * {@code LEFT JOIN} plus a condition in the {@code ON} clause.
 */
public class Ex6EnrollmentReport {

    private final EntityManager em;

    public Ex6EnrollmentReport(EntityManager em) {
        this.em = Objects.requireNonNull(em, "em must not be null");
    }

    /**
     * Courses with at least {@code minimumEnrollments} non-withdrawn enrollments.
     *
     * @param minimumEnrollments the inclusive lower bound on the count
     * @return rows of {@code [String courseCode, Long count]}, ordered by count
     *         descending then code ascending; empty when nothing qualifies,
     *         never {@code null}
     */
    public List<Object[]> popularCourses(int minimumEnrollments) {
        // TODO Exercise 6: one JPQL query with JOIN, WHERE, GROUP BY, HAVING
        //  and ORDER BY. No loops, no filtering in Java.
        throw new UnsupportedOperationException(
                "Exercise 6 not implemented yet - see the Javadoc above for hints.");
    }
}
