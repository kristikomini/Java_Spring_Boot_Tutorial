package it.unicam.cs.enrollment.spring.reporting.dto;

/**
 * One course, ranked against the others in its department.
 *
 * <p>Both ranks are exposed so the difference is visible in the response rather
 * than only in a comment. With three courses of 10, 5 and 5 enrollments:
 *
 * <pre>
 *   enrollments   RANK   DENSE_RANK
 *   10            1      1
 *    5            2      2
 *    5            2      2
 *    2            4      3      &lt;- the gap, and the whole difference
 * </pre>
 *
 * <p>RANK skips to 4 because two courses occupy position 2. DENSE_RANK does not.
 * Neither is correct in general; which one you want depends on whether "third
 * place" should exist when two courses tie for second. ROW_NUMBER, the third
 * member of the family, would give the two tied courses 2 and 3 arbitrarily -
 * useful for deduplication, wrong for a leaderboard.
 */
public interface DepartmentRankRow {

    String getCourseCode();

    String getCourseTitle();

    String getDepartment();

    long getEnrollments();

    int getCapacity();

    int getDepartmentRank();

    int getDepartmentDenseRank();
}
