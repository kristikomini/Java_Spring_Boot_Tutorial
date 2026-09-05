package it.unicam.cs.enrollment.spring.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicam.cs.enrollment.spring.reporting.dto.DepartmentRankRow;
import it.unicam.cs.enrollment.spring.reporting.dto.FunnelRow;
import it.unicam.cs.enrollment.spring.reporting.dto.YearOverYearRow;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * THE REPORTING ENDPOINTS
 * ============================================================================
 * Read-only, and in their own package rather than bolted onto CourseController.
 *
 * <p>That separation is the seam fieldbook chapter 33 calls "a good cut". These
 * endpoints are read-only, tolerate data that is minutes old, and produce the
 * query load you most want OFF the transactional database. Every property that
 * makes reporting a candidate for its own service is visible here, in one
 * package, with its own repository and its own table - which is what a seam
 * looks like BEFORE anybody extracts it.
 *
 * <p>TWO KINDS OF ENDPOINT LIVE HERE, and the distinction is the OLTP/OLAP one
 * that an analytics-flavoured advert is really asking about:
 *
 * <p>LIVE queries - the funnel, the ranking, the year-on-year - run their SQL
 * against the transactional tables on every request. Accurate to the
 * millisecond, and they scan. Fine for a handful of analysts; not fine on a
 * dashboard refreshing every ten seconds during enrollment week.
 *
 * <p>MATERIALISED queries read course_statistics, which the scheduled job wrote.
 * Up to ten minutes stale, and effectively free regardless of how many
 * enrollments exist. They carry a computedAt so a caller can see how stale.
 *
 * <p>Offering both, and being able to say which is which and why, is the whole
 * of what a junior needs to know about the difference.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Analytics over the enrollment data")
@Validated
public class ReportingController {

    private final ReportingRepository reportingRepository;
    private final CourseStatisticsRepository statisticsRepository;
    private final StatisticsRefreshService refreshService;

    public ReportingController(ReportingRepository reportingRepository,
                               CourseStatisticsRepository statisticsRepository,
                               StatisticsRefreshService refreshService) {
        this.reportingRepository = reportingRepository;
        this.statisticsRepository = statisticsRepository;
        this.refreshService = refreshService;
    }

    /**
     * GET /api/v1/reports/funnel?year=2026
     *
     * <p>How many enrollments sit in each state, and what share of the total.
     * The percentage comes from a window function over the grouped counts.
     */
    @GetMapping("/funnel")
    @Operation(summary = "Enrollment counts by status, with percentage of total",
            description = "LIVE query. Accurate now; scans the enrollments table.")
    public List<FunnelRow> funnel(@RequestParam(name = "year", defaultValue = "2026")
                                  @Min(2000) int year) {
        return reportingRepository.enrollmentFunnel(year);
    }

    /**
     * GET /api/v1/reports/department-ranking?year=2026
     *
     * <p>Courses ranked within their department. Returns both RANK and
     * DENSE_RANK so the difference is visible in the response rather than only
     * in a comment - see DepartmentRankRow for the worked example.
     */
    @GetMapping("/department-ranking")
    @Operation(summary = "Courses ranked within their department",
            description = "LIVE query. Window functions: RANK and DENSE_RANK.")
    public List<DepartmentRankRow> departmentRanking(
            @RequestParam(name = "year", defaultValue = "2026") @Min(2000) int year) {
        return reportingRepository.rankCoursesWithinDepartment(year);
    }

    /**
     * GET /api/v1/reports/year-over-year
     *
     * <p>Each course in each year, next to itself in the previous year, via LAG.
     * A course in its first year reports null rather than zero, because unknown
     * is not the same as unchanged.
     */
    @GetMapping("/year-over-year")
    @Operation(summary = "Enrollment change per course, year on year",
            description = "LIVE query. Window function: LAG over a CTE.")
    public List<YearOverYearRow> yearOverYear() {
        return reportingRepository.yearOverYear();
    }

    /**
     * GET /api/v1/reports/course-statistics?year=2026
     *
     * <p>The MATERIALISED report. Reads course_statistics, so it costs one
     * indexed scan of a small table no matter how many enrollments exist.
     *
     * <p>Wrapped rather than returned as a bare list, so computedAt travels with
     * it. A staleness figure the caller cannot see is a staleness figure the
     * caller forgets about, and a dashboard showing numbers without saying when
     * they were true is how somebody makes a decision on data from before the
     * job started failing.
     */
    @GetMapping("/course-statistics")
    @Operation(summary = "Materialised per-course metrics",
            description = "Read from course_statistics. Up to 10 minutes stale; "
                    + "see computedAt in the response.")
    public Map<String, Object> courseStatistics(
            @RequestParam(name = "year", defaultValue = "2026") @Min(2000) int year) {

        List<CourseStatistics> rows =
                statisticsRepository.findByAcademicYearOrderByFillRateDesc(year);

        // LinkedHashMap rather than Map.of: Map.of rejects null values and does
        // not preserve order, and computedAt should be the first thing a reader
        // sees rather than wherever hashing puts it.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("computedAt",
                rows.isEmpty() ? null : rows.get(0).getComputedAt().toString());
        response.put("courseCount", rows.size());
        response.put("courses", rows);
        return response;
    }

    /**
     * GET /api/v1/reports/under-subscribed?year=2026
     *
     * <p>The report a head of department actually opens: emptiest courses first.
     */
    @GetMapping("/under-subscribed")
    @Operation(summary = "Courses with the lowest fill rate",
            description = "Materialised. The report somebody acts on.")
    public List<CourseStatistics> underSubscribed(
            @RequestParam(name = "year", defaultValue = "2026") @Min(2000) int year) {
        return statisticsRepository.findUnderSubscribed(year);
    }

    /**
     * POST /api/v1/reports/refresh
     *
     * <p>Runs the refresh now instead of waiting for the schedule.
     *
     * <p>POST rather than GET, because it changes state. GET must be SAFE, and a
     * report refresh triggered by a browser prefetch or a link crawler is exactly
     * what that rule exists to prevent. Fieldbook chapter 13 has the
     * safe/idempotent table.
     *
     * <p>It IS idempotent: running it twice leaves the same rows. Worth having,
     * because it means a retry after a timeout is always safe.
     *
     * <p>IN PRODUCTION THIS ENDPOINT NEEDS AUTHENTICATION. As it stands it is an
     * unauthenticated way for anyone to make the server run an expensive
     * aggregate as often as they like - a denial of service with a REST
     * interface. It is exposed because there is no security layer in this module
     * at all (chapter 15 makes the same admission about the Jakarta EE
     * application) and because a test needs to trigger the job deterministically
     * rather than waiting ten minutes.
     */
    @PostMapping("/refresh")
    @Operation(summary = "Recompute the materialised statistics now",
            description = "Idempotent. Would require authentication in production.")
    public Map<String, Object> refreshNow() {
        return Map.of("refreshed", refreshService.refreshAll());
    }
}
