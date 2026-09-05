package it.unicam.cs.enrollment.spring.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.Professor;
import it.unicam.cs.enrollment.spring.service.CourseService;
import it.unicam.cs.enrollment.spring.web.dto.CourseV2Response;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;

/**
 * ============================================================================
 * TWO VERSIONS, SERVED AT ONCE
 * ============================================================================
 * The point of this controller is not the code, which is dull. It is that
 * {@code /api/v1/courses/{id}} and {@code /api/v2/courses/{id}} both work, right
 * now, returning different shapes over the same service and the same rows.
 *
 * <pre>
 *   curl -s localhost:8281/api/v1/courses/1 | jq .professorName
 *   curl -s localhost:8281/api/v2/courses/1 | jq .professor.fullName
 * </pre>
 *
 * <p>THAT IS THE WHOLE OF API VERSIONING AS A JUNIOR NEEDS IT. Not a framework
 * feature - two controllers, two DTOs, one service. The hard part was never the
 * routing.
 *
 * <p>THE HARD PART IS RETIRING v1, and it is worth knowing the shape of it
 * because "how would you deprecate an endpoint" is a real interview question
 * with a bad default answer ("send an email"). The steps:
 *
 * <p>1. ANNOUNCE. The {@code Deprecation} and {@code Sunset} response headers
 * (RFC 8594) are the machine-readable way, and they are set below. A client
 * library can log a warning; a monitoring system can alert on them.
 *
 * <p>2. MEASURE. You cannot retire what you cannot count. Every v1 response
 * below increments a Micrometer counter, so /actuator/metrics tells you whether
 * anyone is still calling it - which is the answer to "is it safe to delete".
 * Teams that skip this step keep v1 alive for five years out of fear.
 *
 * <p>3. WAIT, then delete. The waiting period is a business decision about your
 * clients, not a technical one.
 *
 * <p>WHY THE UNVERSIONED PATH STILL EXISTS. {@code /api/courses} is served by
 * CourseController and is an alias for v1, because the Jakarta EE application
 * serves exactly those paths and the two implementations must stay
 * interchangeable. In a greenfield API you would version from the first commit
 * and never have an unversioned path at all - retrofitting one is far more work
 * than starting with it.
 */
@RestController
@RequestMapping("/api/v2/courses")
@Tag(name = "Courses (v2)", description = "Course catalogue - current contract")
public class CourseV2Controller {

    private final CourseService courseService;
    private final Clock clock;

    public CourseV2Controller(CourseService courseService, Clock clock) {
        this.courseService = courseService;
        this.clock = clock;
    }

    /**
     * The springdoc annotations are the only thing this method has that
     * CourseController does not.
     *
     * <p>Note how few there are. springdoc already knows the path, the method,
     * the parameter name and type, and the response schema, because they are in
     * the code. {@code @Operation} adds the prose a generator cannot infer, and
     * {@code @ApiResponse} documents the failures - which is the part worth
     * writing, because the error contract is what a client integrating against
     * you actually needs and is what a generated document is weakest at.
     */
    @GetMapping("/{id}")
    @Operation(summary = "One course, with its professor nested",
            description = "Replaces the flat professorId/professorName of v1.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The course"),
            @ApiResponse(responseCode = "404",
                    description = "No course with that id - RFC 7807 body, "
                            + "errorCode RESOURCE_NOT_FOUND"),
            @ApiResponse(responseCode = "400",
                    description = "The id was not a number - errorCode INVALID_REQUEST")
    })
    public CourseV2Response findById(@PathVariable("id") Long id) {
        Course course = courseService.findByIdWithPrerequisites(id);
        long occupied = courseService.occupiedSeatsFor(List.of(id)).getOrDefault(id, 0L);
        return toV2(course, occupied);
    }

    /**
     * The v1 shape, still served, and now saying so.
     *
     * <p>{@code Deprecation} and {@code Sunset} are standard headers (RFC 8594).
     * Sunset is an HTTP date after which the endpoint may stop answering, and
     * {@code Link rel="successor-version"} points at what to move to. Together
     * they are the difference between deprecating an API and merely intending to.
     *
     * <p>The endpoint still WORKS. That matters: a deprecation that breaks
     * callers is not a deprecation, it is an outage with a polite header.
     */
    @GetMapping("/v1-compat/{id}")
    @Operation(summary = "The v1 shape, deprecated",
            description = "Kept for existing clients. See the Sunset header.",
            deprecated = true)
    public ResponseEntity<CourseV2Response> findByIdDeprecated(@PathVariable("id") Long id) {
        CourseV2Response body = findById(id);
        return ResponseEntity.ok()
                .header("Deprecation", "true")
                .header("Sunset", "Wed, 01 Jul 2026 00:00:00 GMT")
                .header("Link", "</api/v2/courses/" + id + ">; rel=\"successor-version\"")
                .body(body);
    }

    /**
     * Hand-written rather than MapStruct, and for a reason worth naming: three of
     * these fields are computed (occupiedSeats from a separate query,
     * enrollmentOpen from the clock, the nested professor from an association),
     * and a generator would need an {@code expression} for each. That is
     * CourseMapper trade-off again - see EnrollmentMapper for the other side.
     */
    private CourseV2Response toV2(Course course, long occupiedSeats) {
        Professor professor = course.getProfessor();
        return new CourseV2Response(
                course.getId(),
                course.getCode(),
                course.getTitle(),
                course.getDescription(),
                course.getCredits(),
                course.getCapacity(),
                Math.max(0, course.getCapacity() - occupiedSeats),
                occupiedSeats,
                course.getSemester().name(),
                course.getAcademicYear(),
                new CourseV2Response.ProfessorSummary(
                        professor.getId(),
                        professor.getStaffNumber(),
                        professor.fullName(),
                        professor.getTitle().name(),
                        professor.getDepartment()),
                course.getEnrollmentOpensAt(),
                course.getEnrollmentClosesAt(),
                course.isEnrollmentOpen(clock.instant()),
                course.getPrerequisites().stream()
                        .map(Course::getCode)
                        .sorted(Comparator.naturalOrder())
                        .toList());
    }
}
