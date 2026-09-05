package it.unicam.cs.enrollment.spring.repository;

import it.unicam.cs.enrollment.spring.domain.Student;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
