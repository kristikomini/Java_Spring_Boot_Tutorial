package it.unicam.cs.enrollment.spring.reporting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads over the materialised reporting table.
 *
 * <p>Every method is a plain derived query, because the hard work was already
 * done by the aggregate that wrote these rows. That is the point of
 * materialising: the read path becomes trivial.
 */
@Repository
public interface CourseStatisticsRepository extends JpaRepository<CourseStatistics, Long> {

    List<CourseStatistics> findByAcademicYearOrderByFillRateDesc(int academicYear);

    /**
     * The courses in most trouble - lowest fill rate first.
     *
     * <p>The report a head of department actually opens. Note that it filters
     * out courses with no capacity data rather than letting them sort to the
     * top: a report whose worst entries are all artefacts is a report people
     * stop reading.
     */
    @Query("SELECT s FROM CourseStatistics s "
            + "WHERE s.academicYear = :year AND s.capacity > 0 "
            + "ORDER BY s.fillRate ASC")
    List<CourseStatistics> findUnderSubscribed(@Param("year") int year);
}
