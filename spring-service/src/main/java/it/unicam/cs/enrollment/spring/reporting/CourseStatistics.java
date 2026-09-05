package it.unicam.cs.enrollment.spring.reporting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A materialised reporting row. See V6__course_statistics.sql for why this table
 * exists and how it differs from everything else in the schema.
 *
 * <p>NOT extending BaseEntity, and that is a deliberate break from every other
 * entity in this module. BaseEntity brings a surrogate id from a shared
 * sequence, an optimistic-lock version, and created_at/updated_at. A reporting
 * row wants none of them:
 *
 * <p>The course IS the key, so a surrogate id would be ceremony.
 *
 * <p>There is no optimistic locking because there is no concurrent edit to
 * detect - one job writes these rows and nothing else ever touches them. A
 * @Version column here would cost a comparison on every write to protect against
 * a race that cannot happen.
 *
 * <p>created_at and updated_at are replaced by computed_at, which means
 * something different: not "when was this row last written" but "as of when is
 * this number true". A dashboard has to show that value, and a reporting row
 * without it is unusable because nobody can tell live data from the output of a
 * job that has been failing since Friday.
 *
 * <p>Copying a base class into every entity out of habit is how a codebase ends
 * up with version columns on tables nothing updates. The question is always
 * whether this row needs what the base class provides.
 *
 * <p>ASSIGNED ID, not generated: @Id with no @GeneratedValue. The value comes
 * from the course. That has one consequence worth knowing - Spring Data cannot
 * tell a new row from an existing one by looking for a null id, so save() issues
 * a SELECT before every INSERT to find out. For a bulk refresh that is a wasted
 * query per row, which is why the job below uses saveAll in batches and why the
 * table is cleared first.
 */
@Entity
@Table(
        name = "course_statistics",
        indexes = {
                @Index(name = "idx_course_statistics_year_semester",
                        columnList = "academic_year, semester"),
                @Index(name = "idx_course_statistics_fill_rate", columnList = "fill_rate")
        }
)
public class CourseStatistics {

    @Id
    @Column(name = "course_id", nullable = false, updatable = false)
    private Long courseId;

    @Column(name = "course_code", nullable = false, length = 12)
    private String courseCode;

    @Column(name = "course_title", nullable = false, length = 160)
    private String courseTitle;

    @Column(name = "academic_year", nullable = false)
    private int academicYear;

    @Column(name = "semester", nullable = false, length = 10)
    private String semester;

    @Column(name = "department", nullable = false, length = 120)
    private String department;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "total_enrollments", nullable = false)
    private int totalEnrollments;

    @Column(name = "active_count", nullable = false)
    private int activeCount;

    @Column(name = "completed_count", nullable = false)
    private int completedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "withdrawn_count", nullable = false)
    private int withdrawnCount;

    /** NULL when nobody has finished the course. See the NULLIF in the query. */
    @Column(name = "pass_rate", precision = 5, scale = 2)
    private BigDecimal passRate;

    @Column(name = "average_grade", precision = 4, scale = 2)
    private BigDecimal averageGrade;

    @Column(name = "fill_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal fillRate;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected CourseStatistics() {
    }

    public static CourseStatistics from(CourseStatisticsRow row, Instant computedAt) {
        CourseStatistics stats = new CourseStatistics();
        stats.courseId = row.getCourseId();
        stats.courseCode = row.getCourseCode();
        stats.courseTitle = row.getCourseTitle();
        stats.academicYear = row.getAcademicYear();
        stats.semester = row.getSemester();
        stats.department = row.getDepartment();
        stats.capacity = row.getCapacity();
        stats.totalEnrollments = (int) row.getTotalEnrollments();
        stats.activeCount = (int) row.getActiveCount();
        stats.completedCount = (int) row.getCompletedCount();
        stats.failedCount = (int) row.getFailedCount();
        stats.withdrawnCount = (int) row.getWithdrawnCount();
        stats.passRate = row.getPassRate();
        stats.averageGrade = row.getAverageGrade();
        stats.fillRate = row.getFillRate() != null ? row.getFillRate() : BigDecimal.ZERO;
        stats.computedAt = computedAt;
        return stats;
    }

    public Long getCourseId() {
        return courseId;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getCourseTitle() {
        return courseTitle;
    }

    public int getAcademicYear() {
        return academicYear;
    }

    public String getSemester() {
        return semester;
    }

    public String getDepartment() {
        return department;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getTotalEnrollments() {
        return totalEnrollments;
    }

    public int getActiveCount() {
        return activeCount;
    }

    public int getCompletedCount() {
        return completedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public int getWithdrawnCount() {
        return withdrawnCount;
    }

    public BigDecimal getPassRate() {
        return passRate;
    }

    public BigDecimal getAverageGrade() {
        return averageGrade;
    }

    public BigDecimal getFillRate() {
        return fillRate;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
