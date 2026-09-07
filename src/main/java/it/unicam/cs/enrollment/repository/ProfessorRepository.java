package it.unicam.cs.enrollment.repository;

import it.unicam.cs.enrollment.domain.model.Professor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Needed because Course.professor has NO cascade.
 *
 * <p>That is the correct mapping and it has a consequence worth knowing: saving
 * a Course whose Professor has never been persisted throws
 *
 * <pre>
 *   TransientPropertyValueException: Not-null property references a transient
 *   value - transient instance must be saved before current operation
 * </pre>
 *
 * <p>The tempting fix is {@code cascade = CascadeType.PERSIST} on the
 * association, and it is wrong. Fieldbook chapter 09 gives the test: does the
 * child have any meaning without this parent? A professor exists independently
 * of any course they happen to teach, outlives every one of them, and must not
 * be created as a side effect of creating a course - let alone deleted as a side
 * effect of deleting one. Cascade belongs on Student-to-Enrollment, where the
 * enrollment is meaningless without the student. It does not belong here.
 *
 * <p>So the professor is saved first, explicitly, which is one extra line and
 * the honest description of what is happening.
 */
@Repository
public interface ProfessorRepository extends JpaRepository<Professor, Long> {

    Optional<Professor> findByStaffNumber(String staffNumber);

    /**
     * Everyone, in the order a human would list them.
     *
     * <p>A derived query name, not {@code @Query}: Spring Data parses
     * {@code findAllByOrderByLastNameAscFirstNameAsc} and writes the JPQL. The
     * name is long, and that is the trade - it is checked AT STARTUP, so a typo
     * fails the application rather than a request, and there is no query string
     * to drift out of sync with the entity.
     *
     * <p>The rule of thumb: derive it when the name stays readable, write
     * {@code @Query} when it does not. This one is right at the boundary.
     */
    List<Professor> findAllByOrderByLastNameAscFirstNameAsc();
}
