package it.unicam.cs.enrollment.spring.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The person who teaches a course. Mapped to the same {@code professors} table
 * the Jakarta EE application uses.
 *
 * <p>Only the columns this module actually reads are mapped. That is legal and
 * worth understanding: {@code hibernate.ddl-auto=validate} checks that every
 * column you HAVE mapped exists in the database with a compatible type. It does
 * not check the reverse, so a table may hold columns no entity mentions. This is
 * how two services can share a table and each own a slice of it - and also how a
 * column silently stops being written when someone deletes a field.
 */
@Entity
@Table(
        name = "professors",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_professors_staff_number", columnNames = "staff_number"),
                @UniqueConstraint(name = "uk_professors_email", columnNames = "email")
        }
)
public class Professor extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Size(max = 10)
    @Column(name = "staff_number", nullable = false, length = 10, updatable = false)
    private String staffNumber;

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
    @Enumerated(EnumType.STRING)
    @Column(name = "title", nullable = false, length = 30)
    private AcademicTitle title;

    @NotBlank
    @Size(max = 120)
    @Column(name = "department", nullable = false, length = 120)
    private String department;

    /**
     * The inverse side of the Course-to-Professor association: {@code mappedBy}
     * says the {@code professor_id} foreign key on the courses table is what
     * defines the relationship, and this collection merely reads it.
     *
     * <p>LAZY, which is the default for a collection and the right one. Loading
     * a professor should not drag in every course they have ever taught. The
     * price is that touching this set outside a transaction throws
     * {@code LazyInitializationException}, which is the single most common JPA
     * error in both frameworks. Fieldbook chapter 08 has the experiment;
     * CourseRepository shows the fix, which is a JOIN FETCH rather than a
     * disabled lazy flag.
     */
    @OneToMany(mappedBy = "professor", fetch = FetchType.LAZY)
    private Set<Course> courses = new LinkedHashSet<>();

    protected Professor() {
    }

    public Professor(String staffNumber, String firstName, String lastName,
                     Email email, AcademicTitle title, String department) {
        this.staffNumber = staffNumber;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.title = title;
        this.department = department;
    }

    @Transient
    public String fullName() {
        return firstName + " " + lastName;
    }

    public String getStaffNumber() {
        return staffNumber;
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

    public AcademicTitle getTitle() {
        return title;
    }

    public String getDepartment() {
        return department;
    }

    public Set<Course> getCourses() {
        return Collections.unmodifiableSet(courses);
    }

    /**
     * Equality on the business key, not the surrogate id - the same choice the
     * Jakarta EE entity makes, and for the same reason: two Professor objects
     * loaded in different persistence contexts should compare equal if they are
     * the same person.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Professor)) {
            return false;
        }
        return staffNumber != null && staffNumber.equals(((Professor) other).getStaffNumber());
    }

    @Override
    public int hashCode() {
        return Objects.hash(staffNumber);
    }

    @Override
    public String toString() {
        return "Professor{" + staffNumber + ", " + fullName() + "}";
    }
}
