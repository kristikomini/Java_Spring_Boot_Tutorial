package it.unicam.cs.enrollment.spring.web;

import it.unicam.cs.enrollment.spring.domain.AcademicTitle;
import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.Email;
import it.unicam.cs.enrollment.spring.domain.Enrollment;
import it.unicam.cs.enrollment.spring.domain.Professor;
import it.unicam.cs.enrollment.spring.domain.Semester;
import it.unicam.cs.enrollment.spring.domain.Student;
import it.unicam.cs.enrollment.spring.exception.BusinessRuleViolationException;
import it.unicam.cs.enrollment.spring.exception.ResourceNotFoundException;
import it.unicam.cs.enrollment.spring.service.CourseService;
import it.unicam.cs.enrollment.spring.service.EnrollmentService;
import it.unicam.cs.enrollment.spring.web.mapper.CourseMapper;
import it.unicam.cs.enrollment.spring.web.mapper.EnrollmentMapperImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ============================================================================
 * THE WEB SLICE - AND THE TEST THAT PROVES THE TWO SERVICES ARE ONE API
 * ============================================================================
 * {@code @WebMvcTest} starts the controllers, the JSON converters, the
 * validation and the {@code @RestControllerAdvice} - and NOT the services, the
 * repositories or the database. The service layer is mocked, so this file tests
 * exactly one thing: the translation between HTTP and Java.
 *
 * <p>MockMvc calls the dispatcher servlet directly, in-process, with no socket
 * and no port. Faster than a real request and it exercises the real routing,
 * the real Jackson configuration and the real error handling.
 *
 * <p>{@code @MockitoBean} replaces a bean in the context with a Mockito mock. It
 * is the Boot 3.4+ replacement for {@code @MockBean}, which is deprecated - if
 * you meet {@code @MockBean} in an older codebase it is the same idea.
 *
 * <p>THE MOST IMPORTANT ASSERTIONS IN THIS FILE ARE THE FIELD NAMES. Every
 * jsonPath below names a field that the Jakarta EE application also emits. They
 * are what stops the two implementations drifting into two different APIs that
 * merely resemble each other - and they would fail if someone "tidied"
 * {@code errorCode} into {@code code}, or let Jackson serialise a Spring Data
 * Page directly.
 */
@WebMvcTest(controllers = {CourseController.class, EnrollmentController.class})
// EnrollmentMapperImpl, not EnrollmentMapper: MapStruct generates the class at
// compile time and the interface alone is not a bean. Naming the interface here
// fails with "no qualifying bean" and is the usual first MapStruct stumble.
@Import({CourseMapper.class, EnrollmentMapperImpl.class})
@ActiveProfiles("test")
@DisplayName("The HTTP contract")
class CourseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseService courseService;

    @MockitoBean
    private EnrollmentService enrollmentService;

    /**
     * The mappers are real (imported above) but they need a Clock, which lives
     * in a {@code @Configuration} class the web slice does not load. Supplying a
     * fixed one keeps enrollmentOpen deterministic.
     */
    @MockitoBean
    private Clock clock;

    @Test
    @DisplayName("404 carries the full RFC 7807 body, not an empty response")
    void notFoundProducesProblemDetail() throws Exception {
        when(courseService.findByIdWithPrerequisites(anyLong()))
                .thenThrow(ResourceNotFoundException.of("Course", 99L));

        mockMvc.perform(get("/api/courses/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Every one of these field names is shared with the Jakarta EE
                // implementation. This is the contract.
                .andExpect(jsonPath("$.type").value("https://api.unicam.it/problems/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.instance").value("/api/courses/99"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("a full course is 409 with COURSE_FULL, not 400 and not 500")
    void courseFullProduces409() throws Exception {
        when(enrollmentService.enroll(anyLong(), anyLong()))
                .thenThrow(BusinessRuleViolationException.courseFull("CS101", 30));

        mockMvc.perform(post("/api/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":1,\"courseId\":2}"))
                // 409 rather than 400: the request was understood perfectly, the
                // state of the world forbids it. Getting this wrong tells a
                // client to fix its request when it should retry later.
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COURSE_FULL"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(
                        "Course CS101 has reached its capacity of 30 students"));
    }

    @Test
    @DisplayName("a rejected body is 400 and lists EVERY invalid field, not just the first")
    void validationFailureListsAllViolations() throws Exception {
        mockMvc.perform(post("/api/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":null,\"courseId\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                // Two problems, two entries. A user with three bad fields should
                // see three, not submit three times.
                .andExpect(jsonPath("$.violations.length()").value(2))
                .andExpect(jsonPath("$.violations[?(@.field == 'studentId')]").exists())
                .andExpect(jsonPath("$.violations[?(@.field == 'courseId')]").exists());
    }

    @Test
    @DisplayName("a bad enum value is 400 and does not leak the Java type")
    void invalidEnumIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/courses").param("semester", "AUTUMN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("FALL, SPRING")))
                // The negative assertion is the security one: no package names,
                // no class names, no conversion internals in the response body.
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("it.unicam"))));
    }

    @Test
    @DisplayName("a non-numeric path variable is 400, not 500")
    void nonNumericIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/courses/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("a successful enrollment is 201 with a Location header")
    void enrollReturns201WithLocation() throws Exception {
        Enrollment enrollment = Enrollment.create(
                student(), course(), Instant.parse("2026-09-05T10:00:00Z"));

        // The id is assigned by the database, and nothing here has a database.
        // ReflectionTestUtils sets it directly - a standard Spring test utility,
        // and the honest alternative to adding a setId() that production code
        // would then be able to call.
        ReflectionTestUtils.setField(enrollment, "id", 42L);

        when(enrollmentService.enroll(1L, 2L)).thenReturn(enrollment);

        mockMvc.perform(post("/api/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":1,\"courseId\":2}"))
                // 201, not 200: this request created a resource.
                .andExpect(status().isCreated())
                // ...and the response says where it now lives, so the client can
                // follow the header instead of constructing the URL itself.
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.endsWith("/api/enrollments/42")))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.studentNumber").value("S1234567"))
                .andExpect(jsonPath("$.courseCode").value("CS101"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                // The flattening the DTO exists for: no nested student object,
                // no nested course object, no lazy proxy anywhere near Jackson.
                .andExpect(jsonPath("$.student").doesNotExist())
                .andExpect(jsonPath("$.course").doesNotExist())
                // A new enrollment has no grade, so both fields are absent
                // rather than null - see nullFieldsAreOmitted().
                .andExpect(jsonPath("$.grade").doesNotExist())
                .andExpect(jsonPath("$.formattedGrade").value("-"));
    }

    @Test
    @DisplayName("every response carries the correlation id header")
    void correlationIdIsReturned() throws Exception {
        when(courseService.findByIdWithPrerequisites(anyLong()))
                .thenThrow(ResourceNotFoundException.of("Course", 1L));

        mockMvc.perform(get("/api/courses/1").header("X-Correlation-Id", "abc123"))
                .andExpect(header().string("X-Correlation-Id", "abc123"));
    }

    @Test
    @DisplayName("a hostile correlation id is sanitised before it reaches the log")
    void correlationIdIsSanitised() throws Exception {
        when(courseService.findByIdWithPrerequisites(anyLong()))
                .thenThrow(ResourceNotFoundException.of("Course", 1L));

        // Newlines and spaces stripped: without this, an attacker forges whole
        // log lines and makes their own traffic look routine. Log injection.
        mockMvc.perform(get("/api/courses/1")
                        .header("X-Correlation-Id", "abc\n2026-01-01 INFO fake log line"))
                .andExpect(header().string("X-Correlation-Id",
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("\n"))));
    }

    @Test
    @DisplayName("an unexpected failure is 500 with no stack trace in the body")
    void unexpectedFailureLeaksNothing() throws Exception {
        when(courseService.findByIdWithPrerequisites(anyLong()))
                .thenThrow(new IllegalStateException("connection pool exhausted at com.zaxxer.hikari"));

        mockMvc.perform(get("/api/courses/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                // The real message went to the log; the client gets a sentence
                // and a correlation id. Chapter 15 counts the alternative as a
                // genuine finding: internal messages name your libraries, and
                // library versions have published CVEs.
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("hikari"))))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("null fields are omitted, matching JSON-B on the Jakarta EE side")
    void nullFieldsAreOmitted() throws Exception {
        when(courseService.findByIdWithPrerequisites(anyLong()))
                .thenThrow(ResourceNotFoundException.of("Course", 1L));

        mockMvc.perform(get("/api/courses/1"))
                // No violations on a 404, so the field must be ABSENT rather
                // than present-and-null. This is the one assertion that would
                // fail without spring.jackson.default-property-inclusion, and
                // the whole reason that line exists.
                .andExpect(jsonPath("$.violations").doesNotExist());
    }

    @Test
    @DisplayName("an unknown query parameter is ignored rather than rejected")
    void unknownQueryParameterIsIgnored() throws Exception {
        when(courseService.findByYearAndSemester(anyInt(), any(), any())).thenReturn(Page.empty());
        when(courseService.occupiedSeatsFor(any())).thenReturn(Map.of());

        // Tolerant reader: a client sending an extra parameter should not get a
        // 400. Strictness here breaks clients on every deployment that adds a
        // parameter to a URL somebody else also calls.
        mockMvc.perform(get("/api/courses").param("somethingElse", "1"))
                .andExpect(status().isOk())
                // The PageResponse field names, pinned. A Spring Data Page
                // serialised directly would emit "number", "size", "pageable"
                // and "numberOfElements" instead - a different contract from the
                // Jakarta EE service, arrived at by accident.
                .andExpect(jsonPath("$.pageNumber").exists())
                .andExpect(jsonPath("$.pageSize").exists())
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.hasNext").exists())
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    @Test
    @DisplayName("the page size is capped, so ?size=1000000 cannot be used as a weapon")
    void pageSizeIsCapped() throws Exception {
        when(courseService.findByYearAndSemester(anyInt(), any(), any())).thenReturn(Page.empty());
        when(courseService.occupiedSeatsFor(any())).thenReturn(Map.of());

        mockMvc.perform(get("/api/courses").param("size", "1000000"))
                .andExpect(status().isOk());

        // The controller must have asked for at most 100 regardless of what was
        // requested. Without the cap this is an easy denial of service: one URL
        // asks the database for every row and the JVM to hold them.
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(courseService).findByYearAndSemester(anyInt(), any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    // ------------------------------------------------------------------

    private static Student student() {
        return new Student("S1234567", "Giulia", "Rossi",
                Email.of("giulia.rossi@studenti.unicam.it"),
                LocalDate.of(2004, 3, 12), 2025);
    }

    private static Course course() {
        Professor professor = new Professor("P0001", "Marco", "Bianchi",
                Email.of("marco.bianchi@unicam.it"),
                AcademicTitle.ASSOCIATE_PROFESSOR, "Computer Science");
        return new Course("CS101", "Programming", 9, 30,
                Semester.FALL, 2026, professor,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"));
    }
}
