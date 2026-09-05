package it.unicam.cs.enrollment.spring.repository;

import it.unicam.cs.enrollment.spring.domain.Professor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
