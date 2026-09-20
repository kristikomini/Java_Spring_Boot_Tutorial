package it.unicam.cs.enrollment.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQuery;
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
 * A taught course offered in a given academic year and semester.
 *
 * <p>This is the richest entity in the model and the one worth studying most
 * closely. It demonstrates:
 * <ul>
 *   <li>{@code @ManyToOne} - the OWNING side of a foreign key;</li>
 *   <li>a SELF-REFERENCING {@code @ManyToMany} for prerequisites;</li>
 *   <li>{@code @OneToMany} back to the join entity;</li>
 *   <li>{@code @Transient} derived state that is computed, never stored;</li>
 *   <li>time-window business rules expressed on the entity itself.</li>
 * </ul>
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
@NamedQuery(
        name = Course.FIND_BY_CODE_AND_YEAR,
        query = "SELECT c FROM Course c WHERE c.code = :code AND c.academicYear = :academicYear"
)
@NamedQuery(
        name = Course.FIND_OPEN_FOR_ENROLLMENT,
        // JOIN FETCH loads the professor in the SAME query instead of firing an
        // extra SELECT per row later. This is the standard cure for the N+1
        // SELECT problem - the single most common JPA performance bug.
        query = "SELECT c FROM Course c JOIN FETCH c.professor "
                + "WHERE c.enrollmentOpensAt <= :now AND c.enrollmentClosesAt > :now "
                + "ORDER BY c.code ASC"
)
public class Course extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final String FIND_BY_CODE_AND_YEAR = "Course.findByCodeAndYear";
    public static final String FIND_OPEN_FOR_ENROLLMENT = "Course.findOpenForEnrollment";

    /** Natural key together with {@link #academicYear}, e.g. {@code "CS101"}. */
    @NotBlank
    @Size(max = 12)
    @Column(name = "code", nullable = false, length = 12, updatable = false)
    private String code;

    @NotBlank
    @Size(max = 160)
    @Column(name = "title", nullable = false, length = 160)
    private String title;

    /**
     * {@code columnDefinition} escapes to raw DDL. Use it sparingly - it ties
     * the mapping to one database dialect - but a long free-text field is a
     * legitimate case, since the portable default would be {@code VARCHAR(255)}.
     */
    @Size(max = 2000)
    @Column(name = "description", length = 2000)
    private String description;

    /**
     * ECTS credits ({@code CFU} in the Italian system). {@code @Min}/{@code @Max}
     * document the legal range AND enforce it; the {@code CHECK} constraint you
     * would also want lives in the database migration, because validation in
     * the application protects against bugs while a database constraint
     * protects against everything else.
     */
    @Min(1)
    @Max(30)
    @Column(name = "credits", nullable = false)
    private int credits;

    /** Maximum number of seats. The rule enforced against it lives in the service. */
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

    /** Start of the enrollment window (inclusive). */
    @NotNull
    @Column(name = "enrollment_opens_at", nullable = false)
    private Instant enrollmentOpensAt;

    /** End of the enrollment window (exclusive). */
    @NotNull
    @Column(name = "enrollment_closes_at", nullable = false)
    private Instant enrollmentClosesAt;

    /**
     * MANY-TO-ONE - the OWNING side. The {@code professor_id} foreign key column
     * lives in the {@code courses} table, so this field controls the
     * relationship.
     *
     * <p>{@code fetch = LAZY} is written out explicitly even though it is not
     * the default. To-one associations default to EAGER, which is a genuine
     * design mistake in the specification: every time you load a Course you
     * would also load its Professor, whether you need it or not, and those
     * eager loads compound across a graph. Make every association LAZY and use
     * {@code JOIN FETCH} where you actually need the data.
     *
     * <p>{@code optional = false} tells the provider the FK is NOT NULL, which
     * lets it use an inner join instead of a left join.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "professor_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_courses_professor")
    )
    private Professor professor;

    /**
     * SELF-REFERENCING MANY-TO-MANY: courses that must be passed before this
     * one can be taken.
     *
     * <p>A many-to-many always needs a JOIN TABLE (also called an association
     * table). Here both foreign keys point back at {@code courses}, which is why
     * the column names must be spelled out - the defaults would collide.
     *
     * <p>Note this relationship is UNIDIRECTIONAL: a course knows its
     * prerequisites, but not which courses list it as one. Add the reverse side
     * only when a use case needs it. Every bidirectional relationship is another
     * pair of ends to keep in sync.
     *
     * <p>{@code Set} rather than {@code List} matters for many-to-many:
     * Hibernate can then handle additions and removals as single-row
     * INSERT/DELETE statements, whereas a {@code List} with a bag semantic
     * deletes and re-inserts every row on any change.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "course_prerequisites",
            joinColumns = @JoinColumn(name = "course_id",
                    foreignKey = @jakarta.persistence.ForeignKey(name = "fk_prereq_course")),
            inverseJoinColumns = @JoinColumn(name = "prerequisite_id",
                    foreignKey = @jakarta.persistence.ForeignKey(name = "fk_prereq_prerequisite"))
    )
    private Set<Course> prerequisites = new LinkedHashSet<>();

    /** Inverse side of {@code Enrollment.course}. No cascade: see {@link Professor#getCourses()}. */
    @OneToMany(mappedBy = "course", fetch = FetchType.LAZY)
    private Set<Enrollment> enrollments = new LinkedHashSet<>();

    protected Course() {
        // required by JPA
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

    // ------------------------------------------------------------------
    // Domain behaviour
    // ------------------------------------------------------------------

    /**
     * Is the enrollment window open at the given instant?
     *
     * <p>Notice the parameter. The naive version calls {@code Instant.now()}
     * inside the method - and becomes untestable, because a test cannot control
     * the clock. Passing time in ("dependency injection for time") lets a test
     * assert behaviour at any instant. The caller obtains {@code now} from an
     * injected {@link java.time.Clock}; see {@code ClockConfig}.
     *
     * <p>The window is half-open {@code [opens, closes)}: inclusive at the
     * start, exclusive at the end. Half-open intervals compose without
     * off-by-one gaps or overlaps and are the convention worth defaulting to.
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

    public void removePrerequisite(Course prerequisite) {
        this.prerequisites.remove(prerequisite);
    }

    /**
     * DERIVED STATE. {@code @Transient} tells JPA "this is not a column" - it is
     * recomputed from other fields, never stored.
     *
     * <p>Storing a derived value is a decision, not a default: it means two
     * sources of truth that can drift apart. Compute unless you have measured a
     * reason not to.
     *
     * <p><b>Careful:</b> this walks the lazy {@code enrollments} collection, so
     * calling it triggers a SELECT of every enrollment row. That is fine for one
     * course on a detail page and disastrous inside a loop over a list. The
     * service therefore uses a {@code COUNT(*)} query for the capacity check
     * instead of this method - see {@code EnrollmentRepository.countOccupiedSeats}.
     */
    @Transient
    public long occupiedSeats() {
        return enrollments.stream()
                .filter(e -> e.getStatus().occupiesSeat())
                .count();
    }

    @Transient
    public long availableSeats() {
        return Math.max(0, capacity - occupiedSeats());
    }

    @Transient
    public boolean isFull() {
        return availableSeats() == 0;
    }

    /** Human-readable identity, e.g. {@code "CS101 (2025/2026)"}. */
    @Transient
    public String displayCode() {
        return code + " (" + academicYear + "/" + (academicYear + 1) + ")";
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

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

    public void setCredits(int credits) {
        this.credits = credits;
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

    public void setSemester(Semester semester) {
        this.semester = semester;
    }

    public int getAcademicYear() {
        return academicYear;
    }

    public Instant getEnrollmentOpensAt() {
        return enrollmentOpensAt;
    }

    public void setEnrollmentOpensAt(Instant enrollmentOpensAt) {
        this.enrollmentOpensAt = enrollmentOpensAt;
    }

    public Instant getEnrollmentClosesAt() {
        return enrollmentClosesAt;
    }

    public void setEnrollmentClosesAt(Instant enrollmentClosesAt) {
        this.enrollmentClosesAt = enrollmentClosesAt;
    }

    public Professor getProfessor() {
        return professor;
    }

    public void setProfessor(Professor professor) {
        this.professor = professor;
    }

    public Set<Course> getPrerequisites() {
        return Collections.unmodifiableSet(prerequisites);
    }

    public Set<Enrollment> getEnrollments() {
        return Collections.unmodifiableSet(enrollments);
    }

    /** Equality by natural key {@code (code, academicYear)}. */
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
