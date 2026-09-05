package it.unicam.cs.enrollment.spring.reporting.dto;

/**
 * One course in one year, next to the same course in the previous year.
 *
 * <p>The boxed types are load-bearing. Long rather than long for previousYear
 * and delta, because LAG returns NULL for the first row of every partition -
 * the year a course was introduced has no year before it.
 *
 * <p>Declaring these as primitives would either throw on unboxing null or, worse
 * in some mapping paths, silently coerce to 0 and report "no change" for every
 * new course. NULL and zero mean different things here and the type has to be
 * able to tell them apart. Fieldbook chapter 04 makes this argument about
 * Optional; the same argument reaches all the way into a report column.
 */
public interface YearOverYearRow {

    String getCourseCode();

    int getAcademicYear();

    long getEnrollments();

    /** NULL in a course first year - it has no previous year. */
    Long getPreviousYear();

    /** NULL for the same reason. Not zero: "unknown" is not "unchanged". */
    Long getDelta();
}
