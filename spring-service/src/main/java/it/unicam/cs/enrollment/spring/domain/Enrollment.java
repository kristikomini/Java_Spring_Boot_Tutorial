package it.unicam.cs.enrollment.spring.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Objects;

/**
 * The fact that a student took a course. Mapped to the same {@code enrollments}
 * table.
 *
 * <p>THE UNIQUE CONSTRAINT IS THE POINT. {@code uk_enrollments_student_course}
 * is the reason this application cannot enroll the same student twice even if
 * two requests arrive in the same millisecond, on two servers, past every check
 * the service performs. EnrollmentService also looks for an existing row and
 * raises a friendly 409 - but that check is a courtesy to the user, not the
 * safety net. The database is the safety net, and it is the only participant
 * that sees all the traffic.
 *
 * <p>That distinction is fieldbook chapter 07 in one sentence, and it is also
 * why splitting this table away from courses into its own service - the tempting
 * cut in chapter 33 - costs so much: a unique constraint does not span two
 * databases.
 *
 * <p>The state machine is enforced here, in the entity, not in the service. The
 * only way to change status is through a method that asks
 * {@link EnrollmentStatus#canTransitionTo} first, so no caller anywhere can move
 * a COMPLETED enrollment back to ACTIVE. A service could forget; a private
 * setter that does not exist cannot be forgotten.
 */
@Entity
@Table(
        name = "enrollments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_enrollments_student_course",
                columnNames = {"student_id", "course_id"}
        ),
        indexes = {
                @Index(name = "idx_enrollments_course_status", columnList = "course_id, status"),
                @Index(name = "idx_enrollments_student", columnList = "student_id")
        }
)
public class Enrollment extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int MIN_PASSING_GRADE = 18;
    public static final int MAX_GRADE = 30;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_enrollments_student"))
    private Student student;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_enrollments_course"))
    private Course course;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EnrollmentStatus status = EnrollmentStatus.ACTIVE;

    @NotNull
    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private Instant enrolledAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Min(MIN_PASSING_GRADE)
    @Max(MAX_GRADE)
    @Column(name = "grade")
    private Integer grade;

    @Column(name = "with_honours", nullable = false)
    private boolean withHonours;

    protected Enrollment() {
    }

    private Enrollment(Student student, Course course, Instant enrolledAt) {
        this.student = student;
        this.course = course;
        this.enrolledAt = enrolledAt;
        this.status = EnrollmentStatus.ACTIVE;
        this.withHonours = false;
    }

    /**
     * The only way to make one. A private constructor behind a named factory
     * means an Enrollment cannot exist in a half-built state, and the name says
     * what the operation is - which a constructor call never does.
     */
    public static Enrollment create(Student student, Course course, Instant enrolledAt) {
        Objects.requireNonNull(student, "student must not be null");
        Objects.requireNonNull(course, "course must not be null");
        Objects.requireNonNull(enrolledAt, "enrolledAt must not be null");
        return new Enrollment(student, course, enrolledAt);
    }

    public void recordPass(int grade, boolean withHonours, Instant at) {
        transitionTo(EnrollmentStatus.COMPLETED);
        if (grade < MIN_PASSING_GRADE || grade > MAX_GRADE) {
            throw new IllegalArgumentException(
                    "A passing grade must be between " + MIN_PASSING_GRADE
                            + " and " + MAX_GRADE + ", got " + grade);
        }
        if (withHonours && grade != MAX_GRADE) {
            throw new IllegalArgumentException(
                    "Honours (lode) can only be awarded with a grade of " + MAX_GRADE);
        }
        this.grade = grade;
        this.withHonours = withHonours;
        this.completedAt = at;
    }

    public void recordFailure(Instant at) {
        transitionTo(EnrollmentStatus.FAILED);
        this.grade = null;
        this.withHonours = false;
        this.completedAt = at;
    }

    public void withdraw(Instant at) {
        transitionTo(EnrollmentStatus.WITHDRAWN);
        this.completedAt = at;
    }

    public void retake() {
        transitionTo(EnrollmentStatus.ACTIVE);
        this.completedAt = null;
    }

    private void transitionTo(EnrollmentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Illegal enrollment transition: " + status + " -> " + target);
        }
        this.status = target;
    }

    /**
     * A class-level Bean Validation constraint: it checks an invariant across
     * two fields, which no single-field annotation can express.
     *
     * <p>{@code @Transient} keeps JPA from trying to persist the boolean this
     * getter returns. Two specifications, both reading the same method, needing
     * opposite things from it - which is the kind of collision that makes people
     * think annotations are magic. They are not; there are simply two readers.
     */
    @AssertTrue(message = "Honours (lode) requires a grade of exactly 30")
    @Transient
    public boolean isHonoursConsistent() {
        return !withHonours || (grade != null && grade == MAX_GRADE);
    }

    @Transient
    public String formattedGrade() {
        if (grade == null) {
            return "-";
        }
        return withHonours ? grade + " e lode" : String.valueOf(grade);
    }

    public Student getStudent() {
        return student;
    }

    public Course getCourse() {
        return course;
    }

    public EnrollmentStatus getStatus() {
        return status;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Integer getGrade() {
        return grade;
    }

    public boolean isWithHonours() {
        return withHonours;
    }

    @Override
    public String toString() {
        return "Enrollment{student=" + (student != null ? student.getStudentNumber() : null)
                + ", course=" + (course != null ? course.getCode() : null)
                + ", status=" + status
                + ", grade=" + formattedGrade() + "}";
    }
}
