package it.unicam.cs.enrollment.repository;

import it.unicam.cs.enrollment.domain.model.Student;
import it.unicam.cs.enrollment.domain.model.StudentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The whole repository. Four lines of body, and it already has findById, save,
 * findAll, count, existsById, deleteById and the rest of JpaRepository.
 *
 * <p>Worth noticing what @Repository actually does here, because it is not what
 * most people assume. Spring Data would register this interface with or without
 * the annotation - @EnableJpaRepositories scanning finds it either way, and Boot
 * enables that scanning automatically. What the annotation adds is exception
 * translation: it marks the bean for a post-processor that converts provider
 * exceptions (Hibernate ones, JDBC SQLExceptions) into Spring
 * DataAccessException subclasses.
 *
 * <p>That translation is the reason RestExceptionHandler catches
 * DataIntegrityViolationException rather than a Hibernate
 * ConstraintViolationException - and it is a genuine architectural idea, not
 * plumbing: your service layer depends on Spring exceptions, so swapping
 * Hibernate for EclipseLink would not ripple through your catch blocks.
 */
@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByStudentNumber(String studentNumber);

    long countByStatus(StudentStatus status);

    /**
     * Whether an address is already in use.
     *
     * <p>{@code @Query} rather than a derived name, because the address lives
     * inside the {@link it.unicam.cs.enrollment.domain.model.Email} embeddable
     * and the derived-query parser would want
     * {@code existsByEmailValueIgnoreCase} - which works, and is a method name
     * nobody can read. When the name gets longer than the query, write the
     * query.
     *
     * <p>The comparison is against the stored lower-case form, because
     * {@code Email} normalises on construction. Uniqueness that depends on
     * casing is a bug report waiting to happen: two accounts for
     * {@code Mario@x.it} and {@code mario@x.it} are one person and one support
     * ticket.
     */
    @Query("SELECT COUNT(s) > 0 FROM Student s WHERE s.email.value = LOWER(:email)")
    boolean existsByEmail(@Param("email") String email);

    boolean existsByStudentNumber(String studentNumber);

    /**
     * One student with the whole transcript, in ONE query.
     *
     * <p>{@code LEFT JOIN FETCH} is the cure for the N+1 SELECT problem. Without
     * it, loading a student and then touching {@code getEnrollments()} issues a
     * second query - and doing that for twenty students is forty-one queries
     * where two would do. The symptom is an endpoint that is fine in
     * development and unusable with real data.
     *
     * <p>LEFT rather than inner, or a student with no enrollments would vanish
     * from the result entirely. That is a genuinely common bug, and it looks
     * like "the new student does not exist" rather than like a join problem.
     *
     * <p>Two fetches deep, because the caller renders the course of each
     * enrollment. Note that fetching TWO collections in one query is what you
     * must not do - Hibernate would produce a cartesian product, and older
     * versions threw {@code MultipleBagFetchException} instead. Here
     * {@code enrollments} is the only collection; {@code course} is a
     * to-one and free.
     */
    @Query("SELECT DISTINCT s FROM Student s "
            + "LEFT JOIN FETCH s.enrollments e "
            + "LEFT JOIN FETCH e.course "
            + "WHERE s.id = :id")
    Optional<Student> findByIdWithEnrollments(@Param("id") Long id);

    /**
     * The list endpoint's filter: an optional name fragment, an optional status.
     *
     * <h3>Optional filters without building the query by hand</h3>
     * The JPQL equivalent of "ignore this filter when it is null" is the
     * {@code :param IS NULL OR ...} idiom below. It is one readable query
     * rather than four, and - unlike string concatenation - it cannot be made
     * to inject SQL.
     *
     * <p>It has a real cost worth knowing: the database plans ONE query for all
     * four combinations of arguments, so the plan cannot be optimal for each.
     * At small scale that is irrelevant; on a large table with a very selective
     * filter it matters, and the answer is Spring Data's
     * {@code Specification} API - {@code JpaSpecificationExecutor} builds the
     * Criteria query dynamically, so each combination gets its own plan.
     * That is the direct equivalent of the hand-written Criteria code this
     * method replaced.
     *
     * <p>{@code Pageable} carries the sort, so the ordering lives at the call
     * site rather than being baked in here.
     */
    @Query("SELECT s FROM Student s "
            + "WHERE (:nameFragment IS NULL "
            + "       OR LOWER(s.lastName) LIKE LOWER(CONCAT('%', :nameFragment, '%')) "
            + "       OR LOWER(s.firstName) LIKE LOWER(CONCAT('%', :nameFragment, '%'))) "
            + "AND (:status IS NULL OR s.status = :status)")
    Page<Student> search(@Param("nameFragment") String nameFragment,
                         @Param("status") StudentStatus status,
                         Pageable pageable);
}
