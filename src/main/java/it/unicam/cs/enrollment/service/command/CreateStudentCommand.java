package it.unicam.cs.enrollment.service.command;

import java.time.LocalDate;

/**
 * A COMMAND OBJECT: the input to "create a student", as one parameter.
 *
 * <h2>Why not just pass six arguments?</h2>
 * <pre>
 *   createStudent("123456", "Mario", "Rossi", "mario@unicam.it", dob, 2025)
 * </pre>
 * Every argument is a String or an int, so swapping first and last name compiles
 * happily and ships. Adding a field means changing every caller. And at the call
 * site you cannot tell what any value means without opening the method.
 *
 * <h2>Why not pass the REST layer's {@code CreateStudentRequest} DTO?</h2>
 * Because it would point the DEPENDENCY ARROW the wrong way. The service layer
 * is the inner circle; the REST layer is the outer one. Inner layers must never
 * import from outer ones, or the service becomes unusable from a scheduled job,
 * a CLI or a message consumer without dragging JAX-RS along.
 *
 * <p>So the REST layer maps its request DTO to this command. The extra mapping
 * step is the price of keeping the layers genuinely independent - and it is also
 * where the boundary conversion (raw String to {@code Email} value object) has a
 * natural home.
 *
 * <p>This entire class collapses to a single line:
 * <pre>
 *   public record CreateStudentCommand(String studentNumber, ...) {}
 * </pre>
 * The project now targets Java 21, so that IS available - this class is left
 * longhand deliberately, as the side-by-side a record is measured against.
 * Chapter 30 of the fieldbook makes that comparison; converting the DTOs and
 * commands to records is left as an exercise, and the diff is the argument.
 */
public final class CreateStudentCommand {

    private final String studentNumber;
    private final String firstName;
    private final String lastName;
    private final String email;
    private final LocalDate dateOfBirth;
    private final int enrollmentYear;

    public CreateStudentCommand(String studentNumber,
                                String firstName,
                                String lastName,
                                String email,
                                LocalDate dateOfBirth,
                                int enrollmentYear) {
        this.studentNumber = studentNumber;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.dateOfBirth = dateOfBirth;
        this.enrollmentYear = enrollmentYear;
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

    public String getEmail() {
        return email;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public int getEnrollmentYear() {
        return enrollmentYear;
    }
}
