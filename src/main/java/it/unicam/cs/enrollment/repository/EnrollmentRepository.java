package it.unicam.cs.enrollment.repository;

import it.unicam.cs.enrollment.domain.model.Enrollment;
import it.unicam.cs.enrollment.domain.model.EnrollmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The queries the enrollment rules depend on.
 *
 * <p>Every one of these is an aggregate or a filter the DATABASE performs. Read
 * that against {@code Course.occupiedSeats()}, which answers the same question
 * by loading every enrollment row into the JVM and streaming it. Both are in
 * this codebase on purpose; only one of them belongs in a request path.
 */
@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    /**
     * The duplicate check. Derived from the name: {@code student} and
     * {@code course} are association properties, and {@code Id} navigates into
     * them - so this becomes {@code WHERE e.student.id = ?1 AND e.course.id = ?2}
     * without a join to either table, because the foreign keys are already on the
     * enrollments row.
     *
     * <p>Underscores are the explicit way to write that traversal
     * ({@code findByStudent_IdAndCourse_Id}) and are worth knowing: when a
     * property is genuinely called {@code studentId}, Spring Data has to guess
     * whether you meant the field or the traversal, and the underscore is how you
     * settle it.
     */
    Optional<Enrollment> findByStudentIdAndCourseId(Long studentId, Long courseId);

    /**
     * THE SEAT COUNT.
     *
     * <p>One row over the wire regardless of how many students are enrolled.
     * Passing the occupying statuses as a parameter rather than hardcoding
     * {@code IN ('ACTIVE','FAILED')} keeps the rule in
     * {@link EnrollmentStatus#occupiesSeat()}, where it can be read and tested,
     * rather than duplicated into a string.
     *
     * <p>{@code idx_enrollments_course_status} on (course_id, status) is the
     * index that makes this fast, and the column ORDER of that index is not
     * arbitrary - fieldbook chapter 07 explains why an index on (status,
     * course_id) would be nearly useless for this query.
     */
    @Query("SELECT COUNT(e) FROM Enrollment e "
            + "WHERE e.course.id = :courseId AND e.status IN :statuses")
    long countOccupiedSeats(@Param("courseId") Long courseId,
                            @Param("statuses") List<EnrollmentStatus> statuses);

    /**
     * The same count with the statuses supplied from the rule itself.
     *
     * <p>A {@code default} method on a Spring Data interface is ordinary Java:
     * the framework only generates implementations for ABSTRACT methods, so
     * anything with a body is simply inherited. It is the cheapest way to put a
     * convenience on top of a generated query, and it is worth knowing that the
     * option exists before reaching for a custom fragment.
     */
    default long countOccupiedSeats(Long courseId) {
        return countOccupiedSeats(courseId, EnrollmentStatus.occupyingSeats());
    }

    /**
     * Has this student passed a course with this code, in any year?
     *
     * <p>Note that it counts rather than fetching: the caller only needs a
     * boolean, and loading an Enrollment to discard it would drag in the student
     * and course proxies for nothing.
     */
    @Query("SELECT COUNT(e) FROM Enrollment e "
            + "WHERE e.student.id = :studentId "
            + "AND e.course.code = :courseCode "
            + "AND e.status = it.unicam.cs.enrollment.domain.model.EnrollmentStatus.COMPLETED")
    long countCompletedByCourseCode(@Param("studentId") Long studentId,
                                    @Param("courseCode") String courseCode);

    /**
     * The roster. JOIN FETCH on both associations because the response DTO names
     * the student and the course - without it this is the N+1 problem twice over.
     */
    @Query("SELECT e FROM Enrollment e "
            + "JOIN FETCH e.student "
            + "JOIN FETCH e.course c "
            + "JOIN FETCH c.professor "
            + "WHERE e.course.id = :courseId AND e.status = :status "
            + "ORDER BY e.enrolledAt ASC")
    List<Enrollment> findByCourseAndStatus(@Param("courseId") Long courseId,
                                           @Param("status") EnrollmentStatus status);

    /** Everything needed to render one enrollment, in a single query. */
    @Query("SELECT e FROM Enrollment e "
            + "JOIN FETCH e.student "
            + "JOIN FETCH e.course c "
            + "JOIN FETCH c.professor "
            + "WHERE e.id = :id")
    Optional<Enrollment> findByIdWithDetails(@Param("id") Long id);

    /**
     * Seat counts for many courses at once - the projection that keeps the
     * course LIST endpoint at a constant number of queries.
     *
     * <p>Without this, rendering a page of 20 courses would ask
     * {@link #countOccupiedSeats} twenty times. With it, once. The return type is
     * {@code List<Object[]>} because JPQL has no tuple type; each row is
     * {@code [courseId, count]} and the service turns it into a Map.
     *
     * <p>Spring Data can do better than Object[] - an interface or record
     * projection gives you typed accessors - and a real codebase should use one.
     * It is left raw here because it matches the hand-written version exactly,
     * and because seeing Object[] once explains why the projections exist.
     */
    @Query("SELECT e.course.id, COUNT(e) FROM Enrollment e "
            + "WHERE e.course.id IN :courseIds AND e.status IN :statuses "
            + "GROUP BY e.course.id")
    List<Object[]> countOccupiedSeatsByCourse(@Param("courseIds") List<Long> courseIds,
                                              @Param("statuses") List<EnrollmentStatus> statuses);

    /**
     * One student's whole transcript, with everything the API response renders.
     *
     * <p>Three {@code JOIN FETCH}es and no lazy loading afterwards: deciding
     * the FETCH PLAN per use case - rather than making associations EAGER
     * globally - is the difference between an application that scales and one
     * that does not. An EAGER association is a decision made once, for every
     * query, by somebody who could not know what any of them would need.
     */
    @Query("SELECT e FROM Enrollment e "
            + "JOIN FETCH e.student "
            + "JOIN FETCH e.course c "
            + "JOIN FETCH c.professor "
            + "WHERE e.student.id = :studentId "
            + "ORDER BY e.enrolledAt DESC")
    List<Enrollment> findByStudentWithCourse(@Param("studentId") Long studentId);

    /**
     * A BULK UPDATE: closes stale ACTIVE enrollments for courses whose academic
     * year has ended.
     *
     * <h3>{@code @Modifying}, and what happens without it</h3>
     * Spring Data assumes a {@code @Query} is a SELECT and calls
     * {@code getResultList()} on it. An UPDATE reached that way throws at
     * runtime, not at startup, with a message about the query not being a
     * select. {@code @Modifying} switches it to {@code executeUpdate()} and is
     * the annotation everybody forgets exactly once.
     *
     * <p>{@code clearAutomatically = true} matters more than it looks. A bulk
     * update goes straight to the database and does not touch the persistence
     * context, so any {@code Enrollment} already loaded in this transaction
     * still holds the OLD status - and would overwrite the new one on flush.
     * Clearing detaches everything, which is safe here precisely because this
     * runs in a short transaction of its own that does nothing else.
     *
     * <h3>Bulk operations bypass the persistence context - know the consequences</h3>
     * One {@code UPDATE ... WHERE ...} statement is enormously faster than
     * loading 10,000 entities and mutating each. But:
     * <ul>
     *   <li>Entity lifecycle callbacks ({@code @PreUpdate}) do NOT run, so
     *       {@code updatedAt} must be set by hand in the statement.</li>
     *   <li>The {@code @Version} column is NOT incremented, so an entity another
     *       transaction is holding will not notice the change. Hibernate offers
     *       the HQL extension {@code UPDATE VERSIONED Enrollment e SET ...} to
     *       bump it; we stay on portable JPQL here and accept the limitation,
     *       which is safe because this job runs when nothing else is writing.</li>
     * </ul>
     * The rule: use bulk operations from a short, dedicated transaction (like
     * the nightly job) and not in the middle of a request that also manipulates
     * the same entities.
     *
     * @return the number of rows affected
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Enrollment e "
            + "SET e.status = it.unicam.cs.enrollment.domain.model.EnrollmentStatus.WITHDRAWN, "
            + "    e.updatedAt = :now "
            + "WHERE e.status = it.unicam.cs.enrollment.domain.model.EnrollmentStatus.ACTIVE "
            + "AND e.course.academicYear < :year")
    int closeStaleEnrollments(@Param("year") int academicYearBefore, @Param("now") Instant now);
}
