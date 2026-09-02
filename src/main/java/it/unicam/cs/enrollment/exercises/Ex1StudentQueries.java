package it.unicam.cs.enrollment.exercises;

import it.unicam.cs.enrollment.domain.model.Student;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Objects;

/**
 * EXERCISE 1 - Writing a query (the persistence layer)
 * =============================================================================
 * Difficulty: warm-up. Everything you need is in {@code StudentRepository}.
 *
 * <p>Run the tests for this exercise with:
 * <pre>mvn test -Pexercises -Dtest=Ex1StudentQueriesTest</pre>
 *
 * <h2>What to do</h2>
 * Implement {@link #findByEnrollmentYear(int)} so that it returns every student
 * who enrolled in the university in the given year, ordered by student number
 * ascending.
 *
 * <h2>What you are practising</h2>
 * <ul>
 *   <li>JPQL is not SQL. You query <em>entities and fields</em>
 *       ({@code Student s WHERE s.enrollmentYear}), not tables and columns.
 *       Hibernate translates to SQL afterwards.</li>
 *   <li>Bind parameters with {@code setParameter}, never string concatenation.
 *       Chapter 10 of the fieldbook shows what that protects you from.</li>
 *   <li>{@code TypedQuery<Student>} carries the result type, so no cast is
 *       needed and a mismatch is a compile error rather than a runtime one.</li>
 * </ul>
 *
 * <h2>Hints</h2>
 * <ul>
 *   <li>{@code em.createQuery("SELECT s FROM Student s WHERE ...", Student.class)}</li>
 *   <li>{@code ORDER BY s.studentNumber} belongs inside the JPQL string.</li>
 *   <li>{@code getResultList()} returns an empty list, never null, when nothing
 *       matches - so you do not need a null check.</li>
 * </ul>
 *
 * <p>Compare your answer afterwards with {@code StudentRepository.search(...)},
 * which solves a harder version of the same problem with the Criteria API.
 */
public class Ex1StudentQueries {

    private final EntityManager em;

    public Ex1StudentQueries(EntityManager em) {
        this.em = Objects.requireNonNull(em, "em must not be null");
    }

    /**
     * Every student who enrolled in {@code year}, ordered by student number.
     *
     * @param year the enrollment year, e.g. 2024
     * @return matching students, ordered by {@code studentNumber} ascending;
     *         an empty list when none match
     */
    public List<Student> findByEnrollmentYear(int year) {
        // TODO Exercise 1: replace this line with a JPQL query.
        throw new UnsupportedOperationException(
                "Exercise 1 not implemented yet - see the Javadoc above for hints.");
    }
}
