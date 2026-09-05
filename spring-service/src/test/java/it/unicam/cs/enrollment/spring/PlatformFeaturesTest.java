package it.unicam.cs.enrollment.spring;

import it.unicam.cs.enrollment.spring.config.CacheConfig;
import it.unicam.cs.enrollment.spring.domain.AcademicTitle;
import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.Email;
import it.unicam.cs.enrollment.spring.domain.Professor;
import it.unicam.cs.enrollment.spring.domain.Semester;
import it.unicam.cs.enrollment.spring.repository.CourseRepository;
import it.unicam.cs.enrollment.spring.repository.ProfessorRepository;
import it.unicam.cs.enrollment.spring.service.CourseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ============================================================================
 * THE PLATFORM FEATURES - caching, versioning, OpenAPI, metrics
 * ============================================================================
 * A FULL context ({@code @SpringBootTest}) on H2, so every auto-configuration
 * runs and the whole wiring is exercised - but with no Docker and no PostgreSQL,
 * so it stays in the fast `mvn test` loop rather than in `mvn verify`.
 *
 * <p>That middle ground is worth naming, because the usual advice ("unit tests
 * fast, integration tests slow") skips it. Starting the whole context against an
 * in-memory database costs about three seconds and catches an entire class of
 * bug that slice tests cannot: a bean that fails to construct, two beans of the
 * same type with no qualifier, a {@code @Value} pointing at a property nobody
 * set, a cache name that does not exist. Those are startup failures, and a
 * project with only slice tests finds them by deploying.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Caching, API versioning, OpenAPI and metrics")
class PlatformFeaturesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseService courseService;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private ProfessorRepository professorRepository;

    @Autowired
    private CacheManager cacheManager;

    private Long courseId;

    @BeforeEach
    @Transactional
    void seed() {
        // Every test in this class shares one context, so the cache survives
        // between them. Clearing it here is not tidiness - without it, the
        // second test to run gets a hit from the first and proves nothing.
        cacheManager.getCacheNames()
                .forEach(name -> cacheManager.getCache(name).clear());

        courseRepository.deleteAll();
        professorRepository.deleteAll();

        // Saved FIRST, because Course.professor has no cascade - see
        // ProfessorRepository for why that is the right mapping. Skipping this
        // throws TransientPropertyValueException, which names the association
        // rather than the missing save and reads as a mapping error.
        Professor professor = professorRepository.saveAndFlush(
                new Professor("P0001", "Marco", "Bianchi",
                        Email.of("marco.bianchi@unicam.it"),
                        AcademicTitle.ASSOCIATE_PROFESSOR, "Computer Science"));

        Course course = new Course("CS101", "Programming", 9, 30,
                Semester.FALL, 2026, professor,
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2035-01-01T00:00:00Z"));

        courseId = courseRepository.saveAndFlush(course).getId();
    }

    // ------------------------------------------------------------------
    // Caching
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the second lookup of a course is served from the cache")
    void courseDetailIsCached() {
        assertThat(cacheManager.getCache(CacheConfig.COURSE_DETAIL).get(courseId)).isNull();

        Course first = courseService.findByIdWithPrerequisites(courseId);

        // The entry is now there, which is the assertion that actually proves
        // @Cacheable fired. Asserting "the second call returned the same values"
        // would pass whether or not the cache exists.
        assertThat(cacheManager.getCache(CacheConfig.COURSE_DETAIL).get(courseId)).isNotNull();

        Course second = courseService.findByIdWithPrerequisites(courseId);

        // Same OBJECT, not merely an equal one: the cache returned the stored
        // instance without going near Hibernate.
        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("evicting removes the entry, so the next read hits the database")
    void evictionWorks() {
        courseService.findByIdWithPrerequisites(courseId);
        assertThat(cacheManager.getCache(CacheConfig.COURSE_DETAIL).get(courseId)).isNotNull();

        courseService.evictCourse(courseId);

        // If this is null, the eviction annotation is wired correctly. A cache
        // whose eviction silently does nothing is worse than no cache: it serves
        // stale data forever and looks like it is working.
        assertThat(cacheManager.getCache(CacheConfig.COURSE_DETAIL).get(courseId)).isNull();
    }

    @Test
    @DisplayName("the open-course list is a separate cache with its own entry")
    void openCoursesCachedSeparately() {
        courseService.findOpenForEnrollment();

        // sync = true means the key is SimpleKey.EMPTY - the method takes no
        // arguments. Worth seeing once: a no-arg @Cacheable method has exactly
        // one entry, which is why evicting it needs allEntries = true.
        assertThat(cacheManager.getCache(CacheConfig.OPEN_COURSES).getNativeCache())
                .isNotNull();
        assertThat(courseService.findOpenForEnrollment()).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // API versioning
    // ------------------------------------------------------------------

    @Test
    @DisplayName("/api/courses and /api/v1/courses are the same endpoint")
    void unversionedPathIsAnAliasForV1() throws Exception {
        // The unversioned path exists because the Jakarta EE application serves
        // it and the two must stay interchangeable.
        mockMvc.perform(get("/api/courses/" + courseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professorName").value("Marco Bianchi"));

        mockMvc.perform(get("/api/v1/courses/" + courseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professorName").value("Marco Bianchi"));
    }

    @Test
    @DisplayName("v2 nests the professor - the breaking change that justifies the version")
    void v2NestsTheProfessor() throws Exception {
        mockMvc.perform(get("/api/v2/courses/" + courseId))
                .andExpect(status().isOk())
                // The nested object.
                .andExpect(jsonPath("$.professor.fullName").value("Marco Bianchi"))
                .andExpect(jsonPath("$.professor.staffNumber").value("P0001"))
                .andExpect(jsonPath("$.professor.department").value("Computer Science"))
                // The flat fields are GONE. This is what makes it a breaking
                // change rather than an addition: a v1 client reading
                // professorName gets nothing and renders a blank name, with no
                // error anywhere to tell it something went wrong.
                .andExpect(jsonPath("$.professorName").doesNotExist())
                .andExpect(jsonPath("$.professorId").doesNotExist())
                // The additive half: occupiedSeats is new, and adding it would
                // NOT have required a version bump on its own.
                .andExpect(jsonPath("$.occupiedSeats").value(0))
                .andExpect(jsonPath("$.availableSeats").value(30));
    }

    @Test
    @DisplayName("the deprecated endpoint still works, and says so in headers")
    void deprecationIsMachineReadable() throws Exception {
        mockMvc.perform(get("/api/v2/courses/v1-compat/" + courseId))
                // Still 200. A deprecation that breaks callers is not a
                // deprecation, it is an outage with a polite header.
                .andExpect(status().isOk())
                // RFC 8594. A client library can log a warning on these; a
                // monitoring system can alert on them.
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().exists("Sunset"))
                .andExpect(header().string("Link",
                        org.hamcrest.Matchers.containsString("successor-version")));
    }

    // ------------------------------------------------------------------
    // OpenAPI
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the OpenAPI document is generated and describes the real endpoints")
    void openApiDocumentIsGenerated() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(
                        org.hamcrest.Matchers.startsWith("3.")))
                .andExpect(jsonPath("$.info.title").value("UNICAM Course Enrollment API"))
                // Generated FROM the controllers, so these paths are proof the
                // document cannot drift from the code - which is the whole
                // argument for code-first.
                .andExpect(jsonPath("$.paths['/api/v1/courses/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/v2/courses/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/enrollments']").exists());
    }

    @Test
    @DisplayName("the request schema is derived from the Bean Validation constraints")
    void openApiCarriesValidationRules() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // @NotNull and @Positive on the EnrollRequest record become
                // schema constraints with no extra annotation. This is the part
                // that makes generated documentation worth having: the rules a
                // client must satisfy are the rules the server enforces, because
                // they are the same declaration.
                .andExpect(jsonPath("$.components.schemas.EnrollRequest").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.EnrollRequest.properties.studentId").exists());
    }

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Prometheus metrics are exposed, including the pool and the cache")
    void prometheusEndpointPublishesTheMetricsThatMatter() throws Exception {
        // Generate some traffic first, or the HTTP timer has no samples.
        mockMvc.perform(get("/api/courses/" + courseId)).andExpect(status().isOk());

        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Per-endpoint timings, which is where p95 comes from.
        assertThat(body).contains("http_server_requests_seconds");
        // The tag that lets one Prometheus separate several services.
        assertThat(body).contains("application=\"enrollment-spring\"");
        // The pool. hikaricp_connections_pending going above zero is the
        // earliest signal that the pool is the bottleneck - chapter 25.
        assertThat(body).contains("hikaricp_connections");
        // The cache hit ratio, available only because CacheConfig calls
        // recordStats(). A cache you cannot measure is one you cannot defend.
        assertThat(body).contains("cache_gets");
    }

    // ------------------------------------------------------------------
    // The framework exceptions - regression tests for a real bug
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown path is 404 with a problem body, NOT 500")
    void unknownPathIs404() throws Exception {
        // THE BUG THIS CATCHES: without an explicit handler, Spring
        // NoResourceFoundException is caught by @ExceptionHandler(Exception.class)
        // and every typo, stale client and port scan is answered 500 and logged
        // at ERROR with a stack trace. See RestExceptionHandler.handleNoHandler.
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("the wrong verb is 405 with an Allow header, NOT 500")
    void wrongMethodIs405() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.delete("/api/courses/" + courseId))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
                // Required by the HTTP specification on a 405, and one of the
                // most commonly omitted required headers there is.
                .andExpect(header().exists("Allow"));
    }

    @Test
    @DisplayName("malformed JSON is 400, and the parser message is not echoed back")
    void malformedJsonIs400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post("/api/enrollments")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{not json at all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                // The Jackson message quotes the offending input and names the
                // target class. Neither belongs in a response body.
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("EnrollRequest"))));
    }

    @Test
    @DisplayName("/actuator/env is still closed after adding prometheus to the exposure list")
    void addingPrometheusDidNotOpenEverything() throws Exception {
        // A regression guard on a one-word config change. Widening
        // `exposure.include` is exactly the kind of edit where someone reaches
        // for "*" and publishes the datasource password.
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/configprops")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
    }
}
