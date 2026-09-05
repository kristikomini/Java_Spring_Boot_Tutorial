package it.unicam.cs.enrollment.spring.web;

import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.EnrollmentStatus;
import it.unicam.cs.enrollment.spring.domain.Semester;
import it.unicam.cs.enrollment.spring.service.CourseService;
import it.unicam.cs.enrollment.spring.service.EnrollmentService;
import it.unicam.cs.enrollment.spring.web.dto.CourseResponse;
import it.unicam.cs.enrollment.spring.web.dto.EnrollmentResponse;
import it.unicam.cs.enrollment.spring.web.dto.PageResponse;
import it.unicam.cs.enrollment.spring.web.mapper.CourseMapper;
import it.unicam.cs.enrollment.spring.web.mapper.EnrollmentMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ============================================================================
 * THE ROSETTA STONE, AS TWO FILES
 * ============================================================================
 * Open it.unicam.cs.enrollment.api.rest.CourseResource beside this. The two
 * classes do the same work through the same service layer; only the annotations
 * differ, and the mapping is completely mechanical:
 *
 * <pre>
 *   JAX-RS                              Spring MVC
 *   ---------------------------------   ---------------------------------
 *   {@literal @}Path("/courses")                    {@literal @}RequestMapping("/api/courses")
 *   {@literal @}GET                                 {@literal @}GetMapping
 *   {@literal @}POST                                {@literal @}PostMapping
 *   {@literal @}PathParam("id")                     {@literal @}PathVariable("id")
 *   {@literal @}QueryParam("year")                  {@literal @}RequestParam("year")
 *   {@literal @}DefaultValue("2025")                defaultValue = "2025"
 *   (an entity/DTO return)              (the same, or ResponseEntity)
 *   {@literal @}Produces(APPLICATION_JSON)          implied by {@literal @}RestController
 *   {@literal @}Consumes(APPLICATION_JSON)          implied by {@literal @}RequestBody
 *   {@literal @}Context UriInfo                     UriComponentsBuilder / ServletUriComponentsBuilder
 *   {@literal @}RequestScoped                       {@literal @}RestController (singleton - see below)
 * </pre>
 *
 * <p>THE ONE DIFFERENCE THAT IS NOT COSMETIC: SCOPE. The JAX-RS resource is
 * {@code @RequestScoped} - a fresh instance per request, which is why it can
 * safely hold an injected {@code UriInfo} field describing the current request.
 * A Spring {@code @RestController} is a SINGLETON shared by every thread. Give
 * it a mutable field and you have a race condition that appears only under
 * concurrent load and looks like data belonging to the wrong user.
 *
 * <p>So the rule is: controller fields are collaborators, injected once at
 * startup, and everything about the current request arrives as a method
 * parameter. That is fieldbook chapter 06 (one instance, many threads) and
 * chapter 10 (stateless application-scoped beans) arriving from a third
 * direction, and it is the mistake most likely to be made by someone porting
 * JAX-RS code to Spring by find-and-replace.
 *
 * <p>Base path: {@code /api}, matching {@code @ApplicationPath("/api")} on
 * JaxRsActivator. Same paths, different port - which is what makes the two
 * services diffable with curl.
 */
@RestController
// TWO PATHS, ONE CONTROLLER.
//
// "/api/courses" is what the Jakarta EE application serves, and the two
// implementations must stay interchangeable, so it cannot move. "/api/v1/..."
// is the same contract under an explicit version, so new clients can pin one.
//
// An array of paths on @RequestMapping is the whole mechanism - no filter, no
// rewrite rule, no gateway. In a greenfield API you would version from the
// first commit and never need the alias; retrofitting one costs far more than
// starting with it. See CourseV2Controller for what a real version bump looks
// like.
@RequestMapping({"/api/courses", "/api/v1/courses"})
@Tag(name = "Courses (v1)", description = "Course catalogue - frozen contract")
@Validated
public class CourseController {

    private final CourseService courseService;
    private final EnrollmentService enrollmentService;
    private final CourseMapper courseMapper;
    private final EnrollmentMapper enrollmentMapper;

    public CourseController(CourseService courseService,
                            EnrollmentService enrollmentService,
                            CourseMapper courseMapper,
                            EnrollmentMapper enrollmentMapper) {
        this.courseService = courseService;
        this.enrollmentService = enrollmentService;
        this.courseMapper = courseMapper;
        this.enrollmentMapper = enrollmentMapper;
    }

    /**
     * GET /api/courses?year=2025&amp;semester=FALL&amp;page=0&amp;size=20
     *
     * <p>Two queries, always, regardless of page size: one for the courses (plus
     * its count) and one for every seat count at once. The occupied-seats map is
     * the N+1 fix; see CourseService.occupiedSeatsFor.
     *
     * <p>{@code size} is capped at 100 rather than trusted. An uncapped page size
     * is a denial-of-service someone will eventually find by accident:
     * {@code ?size=1000000} asks the database for a million rows and the JVM to
     * hold them. Spring can enforce this globally with
     * {@code spring.data.web.pageable.max-page-size}; it is done here in code so
     * the check is visible next to the thing it protects.
     */
    @GetMapping
    public PageResponse<CourseResponse> list(
            @RequestParam(name = "year", defaultValue = "2025") @Min(2000) int academicYear,
            @RequestParam(name = "semester", required = false) String semester,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) int size) {

        PageRequest pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("code").ascending());
        Page<Course> courses = courseService.findByYearAndSemester(
                academicYear, parseSemester(semester), pageable);

        Map<Long, Long> occupied = courseService.occupiedSeatsFor(
                courses.getContent().stream().map(Course::getId).toList());

        return PageResponse.from(courses,
                course -> courseMapper.toSummary(course, occupied.getOrDefault(course.getId(), 0L)));
    }

    /** GET /api/courses/open - every course currently accepting enrollments. */
    @GetMapping("/open")
    public List<CourseResponse> findOpen() {
        List<Course> courses = courseService.findOpenForEnrollment();
        Map<Long, Long> occupied = courseService.occupiedSeatsFor(
                courses.stream().map(Course::getId).toList());

        return courses.stream()
                .map(c -> courseMapper.toSummary(c, occupied.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    /**
     * GET /api/courses/{id}
     *
     * <p>Returns the DTO directly rather than a ResponseEntity. Spring answers
     * 200 with the serialised body, and if the service throws
     * ResourceNotFoundException the handler turns it into a 404 - so there is no
     * null check and no {@code if (course == null) return notFound()} here.
     * Errors are handled once, in RestExceptionHandler, which is the same
     * argument the JAX-RS exception mappers make.
     */
    @GetMapping("/{id}")
    public CourseResponse findById(@PathVariable("id") Long id) {
        Course course = courseService.findByIdWithPrerequisites(id);
        long occupied = courseService.occupiedSeatsFor(List.of(id)).getOrDefault(id, 0L);
        return courseMapper.toDetail(course, occupied);
    }

    /** GET /api/courses/{id}/enrollments?status=ACTIVE - the roster. */
    @GetMapping("/{id}/enrollments")
    public List<EnrollmentResponse> roster(@PathVariable("id") Long id,
                                           @RequestParam(name = "status", required = false)
                                           String status) {
        return enrollmentMapper.toResponseList(
                enrollmentService.findByCourse(id, parseEnrollmentStatus(status)));
    }

    /**
     * Enum parsing, by hand, for a specific reason.
     *
     * <p>Spring WILL convert a request parameter to an enum automatically - drop
     * the String and declare {@code Semester semester} and it works. What you get
     * for an invalid value is a MethodArgumentTypeMismatchException, whose
     * default message mentions the Java class name and the full type conversion
     * failure. That is an internal detail leaking into an error response, and
     * fieldbook chapter 15 counts it as a real, if minor, information disclosure.
     *
     * <p>Parsing by hand costs six lines and produces "must be one of FALL,
     * SPRING", which tells the caller what to do next. The Jakarta EE version
     * makes the same choice for the same reason.
     */
    private Semester parseSemester(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Semester.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "semester must be one of FALL, SPRING - got '" + raw + "'");
        }
    }

    private EnrollmentStatus parseEnrollmentStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EnrollmentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "status must be one of ACTIVE, COMPLETED, WITHDRAWN, FAILED - got '"
                            + raw + "'");
        }
    }
}
