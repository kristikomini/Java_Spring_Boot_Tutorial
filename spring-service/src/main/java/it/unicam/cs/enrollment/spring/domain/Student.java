package it.unicam.cs.enrollment.spring.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Mapped to the same {@code students} table as the Jakarta EE application.
 *
 * <p>{@link #canEnroll()} is the rule this module actually depends on, and it
 * delegates straight to the status enum. Two levels of one-line delegation look
 * like ceremony until you notice what they buy: EnrollmentService asks the
 * student, the student asks its status, and the condition that decides
 * eligibility is written down exactly once in the codebase. Fieldbook chapter 14
 * contrasts this with the anemic alternative, where the same {@code if} is
 * copied into three services and then diverges.
 */
@Entity
@Table(
        name = "students",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_students_student_number", columnNames = "student_number"),
                @UniqueConstraint(name = "uk_students_email", columnNames = "email")
        },
        indexes = {
                @Index(name = "idx_students_last_name", columnList = "last_name"),
                @Index(name = "idx_students_status", columnList = "status")
        }
)
public class Student extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Size(max = 10)
    @Column(name = "student_number", nullable = false, length = 10, updatable = false)
    private String studentNumber;

    @NotBlank
    @Size(max = 80)
    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @NotBlank
    @Size(max = 80)
    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;

    @Embedded
    private Email email;

    @NotNull
    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StudentStatus status = StudentStatus.ACTIVE;

    @Column(name = "enrollment_year", nullable = false)
    private int enrollmentYear;

    @OneToMany(mappedBy = "student", fetch = FetchType.LAZY)
    private Set<Enrollment> enrollments = new LinkedHashSet<>();

    protected Student() {
    }

    public Student(String studentNumber, String firstName, String lastName,
                   Email email, LocalDate dateOfBirth, int enrollmentYear) {
        this.studentNumber = studentNumber;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.dateOfBirth = dateOfBirth;
        this.enrollmentYear = enrollmentYear;
    }

    /** The one business rule this module needs from Student. */
    public boolean canEnroll() {
        return status.canEnroll();
    }

    @Transient
    public String fullName() {
        return firstName + " " + lastName;
    }

    public String getStudentNumber() {
        return studentNumber;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public Email getEmail() {
        return email;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public StudentStatus getStatus() {
        return status;
    }

    public void setStatus(StudentStatus status) {
        this.status = status;
    }

    public int getEnrollmentYear() {
        return enrollmentYear;
    }

    public Set<Enrollment> getEnrollments() {
        return Collections.unmodifiableSet(enrollments);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Student)) {
            return false;
        }
        return studentNumber != null && studentNumber.equals(((Student) other).getStudentNumber());
    }

    @Override
    public int hashCode() {
        return Objects.hash(studentNumber);
    }

    @Override
    public String toString() {
        return "Student{" + studentNumber + ", " + fullName() + "}";
    }
}
