package it.unicam.cs.enrollment.spring.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A course, mapped to the same {@code courses} table.
 *
 * <p>All four association cardinalities appear in this one class, which is why
 * the Jakarta EE version is the entity fieldbook chapter 08 uses to teach them:
 * {@code @ManyToOne} to the professor, {@code @OneToMany} to the enrollments,
 * {@code @ManyToMany} to the prerequisites, and (on Student) {@code @Embedded}
 * for the single-valued Email. Every one of those annotations is identical here,
 * because they are JPA, not Jakarta EE and not Spring.
 *
 * <p>WHAT IS MISSING, DELIBERATELY: the {@code @NamedQuery} declarations. Over
 * there, five named queries are attached to the entity and referenced by
 * constant. Here the queries live in CourseRepository - some as derived method
 * names, some as {@code @Query}. Same JPQL, different address. Which you prefer
 * is a real argument: named queries are parsed and validated at STARTUP, so a
 * typo fails the deployment rather than the first request that runs it; Spring
 * Data validates {@code @Query} at startup too, but derived method names are
 * checked only against the entity metamodel, not against SQL. Being able to say
 * that sentence is worth more in an interview than a preference.
 */
@Entity
@Table(
        name = "courses",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_courses_code_year",
                columnNames = {"code", "academic_year"}
        ),
        indexes = {
                @Index(name = "idx_courses_semester_year", columnList = "semester, academic_year"),
                @Index(name = "idx_courses_professor", columnList = "professor_id")
        }
)
public class Course extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @Size(max = 12)
    @Column(name = "code", nullable = false, length = 12, updatable = false)
    private String code;

    @NotBlank
    @Size(max = 160)
    @Column(name = "title", nullable = false, length = 160)
    private String title;

    @Size(max = 2000)
    @Column(name = "description", length = 2000)
    private String description;

    @Min(1)
    @Max(30)
    @Column(name = "credits", nullable = false)
    private int credits;

    @Min(1)
    @Max(1000)
    @Column(name = "capacity", nullable = false)
    private int capacity;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "semester", nullable = false, length = 10)
    private Semester semester;

    @Min(2000)
    @Column(name = "academic_year", nullable = false, updatable = false)
    private int academicYear;

    @NotNull
    @Column(name = "enrollment_opens_at", nullable = false)
    private Instant enrollmentOpensAt;

    @NotNull
    @Column(name = "enrollment_closes_at", nullable = false)
    private Instant enrollmentClosesAt;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "professor_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_courses_professor")
    )
    private Professor professor;

    /**
     * A self-referencing many-to-many: a course can require several others, and
     * can itself be required by several others. The join table holds nothing but
     * the two foreign keys.
     *
     * <p>The naming here is not decoration. {@code @ForeignKey(name = ...)} on
     * every association fixes the constraint names, so the error PostgreSQL
     * raises names something you can grep for rather than something Hibernate
     * invented, and so the schema the other application generated and the schema
     * this one validates against agree.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "course_prerequisites",
            joinColumns = @JoinColumn(name = "course_id",
                    foreignKey = @ForeignKey(name = "fk_prereq_course")),
            inverseJoinColumns = @JoinColumn(name = "prerequisite_id",
                    foreignKey = @ForeignKey(name = "fk_prereq_prerequisite"))
    )
    private Set<Course> prerequisites = new LinkedHashSet<>();

    @OneToMany(mappedBy = "course", fetch = FetchType.LAZY)
    private Set<Enrollment> enrollments = new LinkedHashSet<>();

    protected Course() {
    }

    public Course(String code, String title, int credits, int capacity,
                  Semester semester, int academicYear, Professor professor,
                  Instant enrollmentOpensAt, Instant enrollmentClosesAt) {
        this.code = code;
        this.title = title;
        this.credits = credits;
        this.capacity = capacity;
        this.semester = semester;
        this.academicYear = academicYear;
        this.professor = professor;
        this.enrollmentOpensAt = enrollmentOpensAt;
        this.enrollmentClosesAt = enrollmentClosesAt;
    }

    /**
     * The enrollment window, asked of the object that owns the two timestamps.
     *
     * <p>{@code now} is a parameter rather than a call to {@code Instant.now()}
     * inside the method, and that single decision is what makes this rule
     * testable without waiting for September. The clock is an input. Fieldbook
     * chapter 20 makes the general argument; ClockConfig is where this module
     * supplies one.
     */
    public boolean isEnrollmentOpen(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return !now.isBefore(enrollmentOpensAt) && now.isBefore(enrollmentClosesAt);
    }

    public void addPrerequisite(Course prerequisite) {
        Objects.requireNonNull(prerequisite, "prerequisite must not be null");
        if (prerequisite.equals(this)) {
            throw new IllegalArgumentException("A course cannot be its own prerequisite");
        }
        this.prerequisites.add(prerequisite);
    }

    /**
     * THE TRAP, and it is the same one in both applications.
     *
     * <p>This counts seats by streaming the {@code enrollments} collection, which
     * means every enrollment row for this course is loaded into memory first. On
     * a course of 400 students that is 400 objects to answer a question the
     * database answers with a COUNT. Fieldbook chapter 05 names the general
     * version: grouping in Java is a query you decided not to write.
     *
     * <p>So it is here for the domain model to be complete, and the service does
     * NOT use it - EnrollmentService asks
     * {@code EnrollmentRepository.countOccupiedSeats}, which is one row over the
     * wire. Read the two together; the contrast is the lesson.
     */
    @Transient
    public long occupiedSeats() {
        return enrollments.stream()
                .filter(e -> e.getStatus().occupiesSeat())
                .count();
    }

    @Transient
    public String displayCode() {
        return code + " (" + academicYear + "/" + (academicYear + 1) + ")";
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getCredits() {
        return credits;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public Semester getSemester() {
        return semester;
    }

    public int getAcademicYear() {
        return academicYear;
    }

    public Instant getEnrollmentOpensAt() {
        return enrollmentOpensAt;
    }

    public Instant getEnrollmentClosesAt() {
        return enrollmentClosesAt;
    }

    public Professor getProfessor() {
        return professor;
    }

    public Set<Course> getPrerequisites() {
        return Collections.unmodifiableSet(prerequisites);
    }

    public Set<Enrollment> getEnrollments() {
        return Collections.unmodifiableSet(enrollments);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Course)) {
            return false;
        }
        Course that = (Course) other;
        return code != null
                && code.equals(that.getCode())
                && academicYear == that.getAcademicYear();
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, academicYear);
    }

    @Override
    public String toString() {
        return "Course{" + displayCode() + ", " + title + "}";
    }
}
