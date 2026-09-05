package it.unicam.cs.enrollment.spring.reporting;

import java.math.BigDecimal;

/**
 * The aggregate the refresh job reads, before it becomes a CourseStatistics row.
 *
 * <p>Deliberately a separate type from the entity. The query result and the
 * stored row look almost identical today and will not stay that way: the entity
 * gains computed_at, and any change to the query would otherwise mean editing a
 * mapped entity. Keeping the read shape and the write shape apart is the same
 * reasoning that puts a DTO between the entity and the wire.
 */
public interface CourseStatisticsRow {

    Long getCourseId();

    String getCourseCode();

    String getCourseTitle();

    int getAcademicYear();

    String getSemester();

    String getDepartment();

    int getCapacity();

    long getTotalEnrollments();

    long getActiveCount();

    long getCompletedCount();

    long getFailedCount();

    long getWithdrawnCount();

    /** NULL when nobody has finished the course - unknown, not zero. */
    BigDecimal getPassRate();

    /** NULL when no grades have been recorded. */
    BigDecimal getAverageGrade();

    BigDecimal getFillRate();
}
