package it.unicam.cs.enrollment.spring.reporting.dto;

import java.math.BigDecimal;

/**
 * A SPRING DATA INTERFACE PROJECTION - the typed answer to the Object[] problem.
 *
 * <p>EnrollmentRepository.countOccupiedSeatsByCourse returns List&lt;Object[]&gt;
 * and its comment admits that a real codebase should do better. This is better,
 * and the improvement is worth stating: the compiler now checks the field names
 * and the types, so a query whose alias changes breaks the build instead of
 * throwing ClassCastException on a Tuesday.
 *
 * <p>HOW IT WORKS: Spring Data builds a proxy that maps each getter to the
 * query alias derived from its name - getCount() reads the column aliased
 * "count". That is why every column in the native queries has an explicit AS,
 * and why the aliases are camelCase rather than snake_case: they must match the
 * getters, not the database convention.
 *
 * <p>WHY AN INTERFACE AND NOT A RECORD. Spring Data supports both. A record is a
 * DTO projection and needs the constructor parameters to match by name and
 * position, which for a NATIVE query means the alias order matters too - and
 * getting it wrong gives you a runtime failure rather than a compile error. The
 * interface is looser and, for native SQL, more robust. For JPQL, a record is
 * the nicer choice.
 *
 * <p>BigDecimal for the percentage, not double. The column is NUMERIC, and
 * reading a NUMERIC into a double reintroduces exactly the representation error
 * the column type was chosen to avoid. See the note on NUMERIC in
 * V6__course_statistics.sql.
 */
public interface FunnelRow {

    String getStatus();

    long getCount();

    BigDecimal getPercentage();
}
