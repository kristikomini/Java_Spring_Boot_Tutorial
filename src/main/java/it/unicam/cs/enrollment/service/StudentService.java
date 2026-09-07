package it.unicam.cs.enrollment.service;

import it.unicam.cs.enrollment.common.logging.Loggable;
import it.unicam.cs.enrollment.domain.model.Email;
import it.unicam.cs.enrollment.domain.model.Student;
import it.unicam.cs.enrollment.domain.model.StudentStatus;
import it.unicam.cs.enrollment.exception.DuplicateResourceException;
import it.unicam.cs.enrollment.exception.ResourceNotFoundException;
import it.unicam.cs.enrollment.repository.StudentRepository;
import it.unicam.cs.enrollment.service.command.CreateStudentCommand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;

/**
 * Use cases for managing students.
 *
 * <p>A more conventional, less eventful service than {@link EnrollmentService} -
 * mostly CRUD plus a couple of uniqueness rules. Most services in a real system
 * look like this one, and that is fine: not every class needs to be interesting.
 */
@Loggable
@Service
public class StudentService {

    private final StudentRepository studentRepository;
    private final Logger log;

    public StudentService(StudentRepository studentRepository, Logger log) {
        this.studentRepository = studentRepository;
        this.log = log;
    }

    /**
     * Registers a new student.
     *
     * <p>The uniqueness checks below are a UX nicety, not the guarantee - see
     * {@link DuplicateResourceException} for why. The real protection is the
     * UNIQUE constraint declared on the {@code students} table.
     */
    @Transactional
    public Student create(CreateStudentCommand command) {
        if (studentRepository.existsByStudentNumber(command.getStudentNumber())) {
            throw DuplicateResourceException.of("Student", "student number", command.getStudentNumber());
        }

        // Email.of() normalises to lower case. Doing that HERE, at the boundary,
        // is what makes the uniqueness check below meaningful - otherwise
        // "Mario@unicam.it" and "mario@unicam.it" would both be accepted.
        Email email = Email.of(command.getEmail());

        if (studentRepository.existsByEmail(email.getValue())) {
            throw DuplicateResourceException.of("Student", "email", email.getValue());
        }

        Student student = new Student(
                command.getStudentNumber(),
                command.getFirstName(),
                command.getLastName(),
                email,
                command.getDateOfBirth(),
                command.getEnrollmentYear());

        studentRepository.save(student);
        // flush() forces the INSERT now, so the generated id is available before
        // this method returns - the REST layer needs it for the Location header.
        studentRepository.flush();

        log.info("Registered student {} ({})", student.getStudentNumber(), student.fullName());
        return student;
    }

    @Transactional(readOnly = true)
    public Student findById(Long id) {
        return studentRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Student", id));
    }

    /**
     * Loads a student together with the full transcript in one query.
     *
     * <p>A separate method from {@link #findById} on purpose. The caller states
     * which SHAPE of data it needs, and gets exactly that. A single
     * "findById that loads everything" would punish every caller who wanted only
     * the name.
     */
    @Transactional(readOnly = true)
    public Student findByIdWithEnrollments(Long id) {
        return studentRepository.findByIdWithEnrollments(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Student", id));
    }

    @Transactional(readOnly = true)
    public Student findByStudentNumber(String studentNumber) {
        return studentRepository.findByStudentNumber(studentNumber)
                .orElseThrow(() -> ResourceNotFoundException.of("Student", "student number", studentNumber));
    }

    /**
     * The list endpoint, with two optional filters.
     *
     * <p>Blank is normalised to {@code null} HERE rather than in the query,
     * because {@code ?name=} - an empty parameter, which is what a cleared
     * search box sends - means "no filter" and not "match the empty string".
     * A {@code LIKE '%%'} would match everything anyway; the reason to
     * normalise is that the two cases should be one code path, not two that
     * happen to agree.
     */
    @Transactional(readOnly = true)
    public Page<Student> search(String nameFragment, StudentStatus status, Pageable pageable) {
        String filter = (nameFragment == null || nameFragment.trim().isEmpty())
                ? null : nameFragment.trim();
        return studentRepository.search(filter, status, pageable);
    }

    /**
     * Updates the mutable parts of a student.
     *
     * <p>No {@code save()} call: {@code student} is MANAGED, so dirty checking
     * writes the UPDATE at commit. Adding a redundant {@code save()} here would
     * not be wrong, but it would suggest the author does not trust (or does not
     * know about) the persistence context - and one day someone will "fix" a bug
     * by adding a {@code merge()} that quietly detaches something.
     */
    @Transactional
    public Student update(Long id, String firstName, String lastName, String email) {
        Student student = findById(id);

        if (firstName != null) {
            student.setFirstName(firstName);
        }
        if (lastName != null) {
            student.setLastName(lastName);
        }
        if (email != null) {
            Email newEmail = Email.of(email);
            if (!newEmail.equals(student.getEmail())
                    && studentRepository.existsByEmail(newEmail.getValue())) {
                throw DuplicateResourceException.of("Student", "email", newEmail.getValue());
            }
            student.setEmail(newEmail);
        }

        log.info("Updated student {}", student.getStudentNumber());
        return student;
    }

    @Transactional
    public Student suspend(Long id) {
        Student student = findById(id);
        student.suspend();
        log.info("Suspended student {}", student.getStudentNumber());
        return student;
    }

    @Transactional
    public Student reinstate(Long id) {
        Student student = findById(id);
        student.reinstate();
        log.info("Reinstated student {}", student.getStudentNumber());
        return student;
    }

    @Transactional
    public long countByStatus(StudentStatus status) {
        return studentRepository.countByStatus(status);
    }

    /**
     * Deletes a student and, by cascade, every enrollment they hold.
     *
     * <p>{@code cascade = ALL} plus {@code orphanRemoval} on
     * {@code Student.enrollments} makes that happen automatically. Be deliberate
     * about this: cascading deletes are convenient and are also how people
     * accidentally erase half a database. A real university system would almost
     * certainly SOFT DELETE instead - set a {@code deletedAt} timestamp and
     * filter it out - because academic records must be retained.
     */
    @Transactional
    public void delete(Long id) {
        Student student = findById(id);
        studentRepository.delete(student);
        log.warn("Deleted student {} and all associated enrollments", student.getStudentNumber());
    }
}
