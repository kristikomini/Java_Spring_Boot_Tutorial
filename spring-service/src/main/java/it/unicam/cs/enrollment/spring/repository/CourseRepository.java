package it.unicam.cs.enrollment.spring.repository;

import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.Semester;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * ============================================================================
 * THE REPOSITORY YOU DO NOT WRITE
 * ============================================================================
 * Open ../../../../../../../../src/main/java/it/unicam/cs/enrollment/repository/CourseRepository.java
 * beside this file. That one is a class of about 80 lines: it extends
 * AbstractJpaRepository, holds an injected EntityManager, and each method builds
 * a TypedQuery, sets parameters and calls getResultList().
 *
 * <p>This is an INTERFACE with no implementation anywhere in the repository.
 * At startup Spring Data creates a proxy that implements it, and the proxy knows
 * what to do because of three different mechanisms visible below. Being able to
 * name all three is the difference between "Spring Data is magic" and knowing
 * what you are looking at.
 *
 * <p>1. INHERITED METHODS. {@code JpaRepository<Course, Long>} already provides
 *    findById, findAll, save, delete, count, existsById and about twenty more.
 *    The whole of AbstractJpaRepository, gone - which is the honest summary of
 *    why teams pick Spring Data.
 *
 * <p>2. DERIVED QUERIES. {@code findByCodeAndAcademicYear} has no body and no
 *    annotation. Spring Data parses the METHOD NAME - findBy, then the property
 *    {@code code}, then And, then {@code academicYear} - and generates the JPQL.
 *    It validates the property names against the entity metamodel at startup, so
 *    a typo fails the application on boot, not on the first request.
 *
 * <p>3. EXPLICIT {@code @Query}. When the name would become unreadable, or when
 *    you need JOIN FETCH (which no method name can express), write the JPQL. This
 *    is the SAME JPQL as the named queries on the Jakarta EE entity, moved from
 *    the entity to the repository.
 *
 * <p>THE THING TO SAY IN AN INTERVIEW: derived queries are wonderful until the
 * name is longer than the query. {@code findByCodeAndAcademicYearAndSemesterOrderByTitleAsc}
 * is a real method name that real codebases contain, and it is worse than the
 * four lines of JPQL it replaces. The rule most teams settle on is: derive it
 * while the name stays readable, annotate it after that.
 */
@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {

    /**
     * Mechanism 2 - derived from the method name. No body, no JPQL.
     *
     * <p>Returning {@code Optional<Course>} rather than {@code Course} is Spring
     * Data honouring the same contract the hand-written repository implements
     * with its {@code singleResult} helper: absence is a normal result, and the
     * caller is made to say what it means. The JPA alternative,
     * {@code getSingleResult()}, throws NoResultException - an exception for a
     * case that is not exceptional.
     */
    Optional<Course> findByCodeAndAcademicYear(String code, int academicYear);

    /** Also derived. Spring Data understands the {@code exists} prefix too. */
    boolean existsByCodeAndAcademicYear(String code, int academicYear);

    /**
     * Mechanism 3 - explicit JPQL, because of the JOIN FETCH.
     *
     * <p>This is the N+1 fix, and it is the single most valuable line in the
     * file. Without {@code JOIN FETCH c.professor}, listing 20 courses runs one
     * query for the courses and then 20 more - one per course - the moment the
     * mapper asks for {@code course.getProfessor().fullName()}. With it, one
     * query. Fieldbook chapter 08 has the experiment; the important part is that
     * NO METHOD NAME CAN EXPRESS THIS. Fetching strategy is not a filter, so
     * deriving queries from names cannot reach it, and this is precisely where
     * teams that only ever use derived queries end up with a slow application
     * and no idea why.
     */
    @Query("SELECT c FROM Course c JOIN FETCH c.professor "
            + "WHERE c.enrollmentOpensAt <= :now AND c.enrollmentClosesAt > :now "
            + "ORDER BY c.code ASC")
    List<Course> findOpenForEnrollment(@Param("now") Instant now);

    /**
     * LEFT JOIN FETCH, because a course may legitimately have no prerequisites
     * and an inner join would silently drop it from the result. That one word is
     * a bug people ship regularly.
     *
     * <p>{@code DISTINCT} because fetching a collection multiplies the rows: a
     * course with three prerequisites comes back as three identical Course rows.
     * Hibernate would hand you the same object three times in the list.
     */
    @Query("SELECT DISTINCT c FROM Course c "
            + "JOIN FETCH c.professor "
            + "LEFT JOIN FETCH c.prerequisites "
            + "WHERE c.id = :id")
    Optional<Course> findByIdWithPrerequisites(@Param("id") Long id);

    /**
     * THE PESSIMISTIC LOCK - the mechanism the whole seat-counting rule rests on.
     *
     * <p>{@code @Lock(PESSIMISTIC_WRITE)} makes Hibernate emit
     * {@code SELECT ... FOR UPDATE}. The row is held until the transaction ends,
     * so a second request asking for the same course blocks rather than reading a
     * seat count that is about to be wrong. This is what stops two students
     * taking the last seat simultaneously.
     *
     * <p>The Jakarta EE version says the same thing as
     * {@code em.find(Course.class, id, LockModeType.PESSIMISTIC_WRITE)}. Identical
     * SQL, identical semantics, three words of annotation instead of a method
     * call - which is a fair summary of the whole framework difference.
     *
     * <p>THE TRAP: this only works if a transaction is already open. Call it
     * outside one and, depending on the provider, you get either an exception or
     * a lock that is released immediately and protects nothing. The lock belongs
     * to the transaction, not to the query.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Course c WHERE c.id = :id")
    Optional<Course> findByIdForUpdate(@Param("id") Long id);

    /**
     * PAGINATION, which is where Spring Data saves the most code.
     *
     * <p>The hand-written version needs about 25 lines: build the JPQL, set
     * firstResult and maxResults, then build a SECOND count query with the same
     * WHERE clause, run it, and assemble a Page. Getting the two WHERE clauses
     * out of step is a classic bug - the list is filtered and the total is not.
     *
     * <p>Here, {@code Pageable} in and {@code Page} out. Spring Data derives the
     * count query from this one automatically.
     *
     * <p>Except when it cannot. A JOIN FETCH plus a Pageable is the known sharp
     * edge: the derived count query inherits the fetch join and either fails or
     * counts wrongly, and Hibernate may resort to paginating IN MEMORY - it logs
     * "firstResult/maxResults specified with collection fetch; applying in
     * memory", which means it loaded every matching row before discarding all but
     * twenty. On a large table that is an outage. The fix is
     * {@code countQuery}, written out explicitly below, which is why this method
     * has two queries after all - but declared once, next to each other, where
     * they can be seen to agree.
     */
    @Query(value = "SELECT c FROM Course c JOIN FETCH c.professor "
            + "WHERE c.academicYear = :academicYear "
            + "AND (:semester IS NULL OR c.semester = :semester)",
            countQuery = "SELECT COUNT(c) FROM Course c "
                    + "WHERE c.academicYear = :academicYear "
                    + "AND (:semester IS NULL OR c.semester = :semester)")
    Page<Course> findByYearAndOptionalSemester(@Param("academicYear") int academicYear,
                                               @Param("semester") Semester semester,
                                               Pageable pageable);
}
