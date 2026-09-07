package it.unicam.cs.enrollment.web.dto;

import it.unicam.cs.enrollment.domain.validation.StudentNumber;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * The JSON body accepted by {@code POST /api/students}.
 *
 * <h2>Why a DTO instead of the {@code Student} entity?</h2>
 * Accepting an entity directly at the HTTP boundary is one of the most common
 * and most damaging shortcuts in enterprise Java. Four separate problems:
 * <ol>
 *   <li><b>MASS ASSIGNMENT.</b> If the entity is the request body, a caller can
 *       send {@code {"id": 1, "version": 99, "status": "GRADUATED"}} and set
 *       fields you never meant to expose. This is a genuine security
 *       vulnerability, not a style preference.</li>
 *   <li><b>COUPLING.</b> Your database schema becomes your public API. Renaming
 *       a column breaks every client.</li>
 *   <li><b>Different shapes.</b> Creating a student needs no id; the response
 *       must include one. One class cannot honestly describe both.</li>
 *   <li><b>Lazy loading.</b> Serialising an entity walks its associations and
 *       either triggers a cascade of queries or throws
 *       {@code LazyInitializationException} once the transaction has closed.</li>
 * </ol>
 *
 * <h2>Requirements JSON-B places on this class</h2>
 * A public no-argument constructor and public getters/setters for every field it
 * must populate. That is why this class has mutable fields while most other
 * classes in the project are immutable - it is a deserialisation target, and the
 * framework needs somewhere to put the values.
 */
public class CreateStudentRequest {

    /**
     * Constraints are declared HERE as well as on the entity. That is deliberate
     * duplication, not an oversight:
     * <ul>
     *   <li>the DTO's constraints run at the HTTP boundary and produce a clean
     *       400 with per-field messages;</li>
     *   <li>the entity's constraints run at {@code @PrePersist} and protect the
     *       database from every path, including scheduled jobs and imports.</li>
     * </ul>
     * They are two different lines of defence with two different audiences.
     */
    @NotNull(message = "studentNumber is required")
    @StudentNumber
    private String studentNumber;

    @NotBlank(message = "firstName is required")
    @Size(max = 80, message = "firstName must be at most {max} characters")
    private String firstName;

    @NotBlank(message = "lastName is required")
    @Size(max = 80, message = "lastName must be at most {max} characters")
    private String lastName;

    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid address")
    @Size(max = 255)
    private String email;

    @NotNull(message = "dateOfBirth is required")
    @Past(message = "dateOfBirth must be in the past")
    private LocalDate dateOfBirth;

    @Min(value = 2000, message = "enrollmentYear must be {value} or later")
    @Max(value = 2100, message = "enrollmentYear must be {value} or earlier")
    private int enrollmentYear;

    /** Required by JSON-B for deserialisation. */
    public CreateStudentRequest() {
        // required by JSON-B
    }

    public String getStudentNumber() {
        return studentNumber;
    }

    public void setStudentNumber(String studentNumber) {
        this.studentNumber = studentNumber;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public int getEnrollmentYear() {
        return enrollmentYear;
    }

    public void setEnrollmentYear(int enrollmentYear) {
        this.enrollmentYear = enrollmentYear;
    }
}
