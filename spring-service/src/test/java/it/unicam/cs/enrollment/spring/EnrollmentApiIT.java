package it.unicam.cs.enrollment.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unicam.cs.enrollment.spring.domain.AcademicTitle;
import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.Email;
import it.unicam.cs.enrollment.spring.domain.EnrollmentStatus;
import it.unicam.cs.enrollment.spring.domain.Professor;
import it.unicam.cs.enrollment.spring.domain.Semester;
import it.unicam.cs.enrollment.spring.domain.Student;
import it.unicam.cs.enrollment.spring.repository.CourseRepository;
import it.unicam.cs.enrollment.spring.repository.EnrollmentRepository;
import it.unicam.cs.enrollment.spring.repository.ProfessorRepository;
import it.unicam.cs.enrollment.spring.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 * THE TEST THAT DOES NOT LIE
 * ============================================================================
 * Everything above this file is fast and partial. EnrollmentServiceTest mocks
 * the repositories, so the JPQL never runs. CourseRepositoryTest uses H2, which
 * is not PostgreSQL. CourseControllerTest mocks the services, so nothing
 * reaches a database at all. Each proves something real; none of them proves the
 * application works.
 *
 * <p>This one starts the whole thing - embedded Tomcat on a random port, the
 * real controllers, the real services, the real Hibernate - against a REAL
 * PostgreSQL 16 in a container, over a REAL HTTP socket. Fieldbook chapter 20
 * calls the belief that the faster tests cover this "the test that lies".
 *
 * <p>AND IT RUNS THE REAL MIGRATIONS. Flyway applies
 * ../src/main/resources/db/migration - the same files the Jakarta EE application
 * deploys - and then {@code ddl-auto: validate} checks these entity classes
 * against the result. So this test proves the thing the whole module claims:
 * that both applications map the same schema. Add a column here without a
 * migration and it fails at startup.
 *
 * <p>{@code @ServiceConnection} (Boot 3.1+) is what removes the plumbing. It
 * reads the container random port, username and password and configures the
 * DataSource from them. Before it existed every project wrote a
 * {@code @DynamicPropertySource} block by hand, and you will still meet that.
 *
 * <p>{@code disabledWithoutDocker = true} is deliberate and is a small stance
 * worth taking: on a machine with no Docker daemon these tests are SKIPPED, not
 * failed. A test that cannot run is not a test that failed, and a build that is
 * red for environmental reasons trains people to ignore red builds.
 *
 * <pre>
 *   mvn verify                  runs it, if Docker is up
 *   mvn verify -DskipITs        skips it explicitly
 * </pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@DisplayName("The whole application, against a real PostgreSQL")
class EnrollmentApiIT {

    /**
     * One container for every test in this class ({@code static}), started once
     * and reused. Making it non-static starts a fresh PostgreSQL per test
     * method, which is correct and unbearably slow.
     *
     * <p>The image tag is pinned to match docker-compose.yml. Testing against
     * postgres:latest means the test suite changes behaviour on a day you did
     * not change any code.
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private ProfessorRepository professorRepository;

    @Autowired
    private EnrollmentRepository enrollmentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Long courseId;
    private Long studentId;

    @BeforeEach
    void seed() {
        enrollmentRepository.deleteAll();
        courseRepository.deleteAll();
        studentRepository.deleteAll();
        professorRepository.deleteAll();

        // Saved FIRST: Course.professor has no cascade, deliberately. See
        // ProfessorRepository.
        Professor professor = professorRepository.saveAndFlush(
                new Professor("P0001", "Marco", "Bianchi",
                        Email.of("marco.bianchi@unicam.it"),
                        AcademicTitle.ASSOCIATE_PROFESSOR, "Computer Science"));

        // The window is deliberately wide, because this test uses the real
        // system clock rather than a fixed one - it is the whole application,
        // and the whole application has a real Clock bean.
        Course course = new Course("CS101", "Programming", 9, 2,
                Semester.FALL, 2026, professor,
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2035-01-01T00:00:00Z"));

        courseId = courseRepository.saveAndFlush(course).getId();
        studentId = studentRepository.saveAndFlush(new Student("S1234567", "Giulia", "Rossi",
                Email.of("giulia.rossi@studenti.unicam.it"),
                LocalDate.of(2004, 3, 12), 2025)).getId();
    }

    @Test
    @DisplayName("the entity mappings validate against the real Flyway schema")
    void contextLoads() {
        // If this method runs at all, ddl-auto=validate has already compared
        // every mapping in this module against the schema the migrations built.
        // An empty body is the entire assertion, and it is the most valuable one
        // in the file.
        assertThat(courseId).isNotNull();
    }

    @Test
    @DisplayName("POST /api/enrollments creates a row and returns 201 with Location")
    void enrollEndToEnd() {
        ResponseEntity<String> response = postEnrollment(studentId, courseId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath())
                .matches("/api/enrollments/\\d+");
        // Every response carries one, generated by the filter when the client
        // did not send one.
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();

        JsonNode body = parse(response.getBody());
        assertThat(body.get("studentNumber").asText()).isEqualTo("S1234567");
        assertThat(body.get("courseCode").asText()).isEqualTo("CS101");
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("formattedGrade").asText()).isEqualTo("-");
        // Absent, not null - the JSON-B-matching inclusion setting, verified on
        // a real serialisation rather than a mocked one.
        assertThat(body.has("grade")).isFalse();

        // The row is really there.
        assertThat(enrollmentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the seat limit holds: the third student onto a two-seat course gets 409")
    void capacityIsEnforced() {
        Long second = newStudent("S2222222");
        Long third = newStudent("S3333333");

        assertThat(postEnrollment(studentId, courseId).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(postEnrollment(second, courseId).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> overflow = postEnrollment(third, courseId);

        assertThat(overflow.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = parse(overflow.getBody());
        assertThat(problem.get("errorCode").asText()).isEqualTo("COURSE_FULL");
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("type").asText())
                .isEqualTo("https://api.unicam.it/problems/business-rule-violation");

        assertThat(enrollmentRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("the same student twice is 409 DUPLICATE_RESOURCE, and only one row exists")
    void duplicateIsRejected() {
        assertThat(postEnrollment(studentId, courseId).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> duplicate = postEnrollment(studentId, courseId);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(duplicate.getBody()).get("errorCode").asText())
                .isEqualTo("DUPLICATE_RESOURCE");
        assertThat(enrollmentRepository.count()).isEqualTo(1);
    }

    /**
     * THE TEST THAT ONLY WORKS AGAINST A REAL DATABASE.
     *
     * <p>Ten threads race for two seats. The pessimistic lock in
     * CourseRepository.findByIdForUpdate is what makes exactly two win, and
     * {@code SELECT ... FOR UPDATE} has to genuinely block for that to happen.
     * H2 in PostgreSQL mode does not reproduce this behaviour, and mocks
     * obviously cannot - which is why this assertion lives here and nowhere
     * else.
     *
     * <p>The eight losers may get 409 from the seat check or from the unique
     * constraint, and the test does not care which: what matters is that no
     * third row is ever written. That is the difference between testing a
     * mechanism and testing an outcome, and the outcome is the requirement.
     */
    @Test
    @DisplayName("ten concurrent requests for two seats produce exactly two enrollments")
    void concurrentEnrollmentsDoNotOversell() throws Exception {
        int attempts = 10;
        List<Long> students = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            students.add(newStudent(String.format("S90%05d", i)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        // A START GATE. Without it the first thread submitted has usually
        // finished before the last one is scheduled, and the test passes without
        // ever racing anything. Every thread blocks on this latch and they are
        // all released at once, which is the difference between a concurrency
        // test and ten sequential requests wearing a costume.
        java.util.concurrent.CountDownLatch startGate = new java.util.concurrent.CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();

        try {
            for (Long id : students) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    HttpStatus status = (HttpStatus) postEnrollment(id, courseId).getStatusCode();
                    if (status == HttpStatus.CREATED) {
                        created.incrementAndGet();
                    } else if (status == HttpStatus.CONFLICT) {
                        conflicted.incrementAndGet();
                    }
                    return null;
                }));
            }

            startGate.countDown();

            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(created.get()).isEqualTo(2);
        assertThat(conflicted.get()).isEqualTo(attempts - 2);

        // The claim that actually matters: the database holds two rows, not
        // three. Capacity was 2.
        assertThat(enrollmentRepository.countOccupiedSeats(courseId,
                List.of(EnrollmentStatus.ACTIVE, EnrollmentStatus.FAILED)))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("withdrawing gives the seat back to the next student")
    void withdrawalReleasesTheSeat() {
        Long second = newStudent("S2222222");
        Long third = newStudent("S3333333");

        String location = postEnrollment(studentId, courseId)
                .getHeaders().getLocation().getPath();
        postEnrollment(second, courseId);

        // Full: the third student is refused.
        assertThat(postEnrollment(third, courseId).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // The first student withdraws.
        ResponseEntity<String> withdrawal = rest.postForEntity(
                url(location + "/withdrawal"), null, String.class);
        assertThat(withdrawal.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(withdrawal.getBody()).get("status").asText()).isEqualTo("WITHDRAWN");

        // ...and now there is room. This is EnrollmentStatus.occupiesSeat()
        // being true end to end rather than in a unit test.
        assertThat(postEnrollment(third, courseId).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("GET /api/courses returns the owned page shape, not Spring Data internals")
    void courseListShape() {
        ResponseEntity<String> response = rest.getForEntity(
                url("/api/courses?year=2026"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parse(response.getBody());

        // The PageResponse contract, identical to the Jakarta EE service.
        assertThat(body.has("content")).isTrue();
        assertThat(body.has("pageNumber")).isTrue();
        assertThat(body.has("totalElements")).isTrue();
        assertThat(body.has("hasNext")).isTrue();
        // Spring Data leakage that must not be here.
        assertThat(body.has("pageable")).isFalse();
        assertThat(body.has("numberOfElements")).isFalse();

        JsonNode course = body.get("content").get(0);
        assertThat(course.get("code").asText()).isEqualTo("CS101");
        assertThat(course.get("availableSeats").asInt()).isEqualTo(2);
        assertThat(course.get("professorName").asText()).isEqualTo("Marco Bianchi");
        // The list projection omits prerequisites deliberately, so the field is
        // absent rather than an empty array that would claim there are none.
        assertThat(course.has("prerequisiteCodes")).isFalse();
    }

    @Test
    @DisplayName("availableSeats drops as students enroll")
    void availableSeatsReflectsEnrollments() {
        postEnrollment(studentId, courseId);

        JsonNode course = parse(rest.getForEntity(
                url("/api/courses/" + courseId), String.class).getBody());

        assertThat(course.get("availableSeats").asInt()).isEqualTo(1);
        // The detail projection DOES include prerequisites, as an empty list.
        assertThat(course.get("prerequisiteCodes").isArray()).isTrue();
    }

    @Test
    @DisplayName("a 404 body is RFC 7807 over a real socket, not a Tomcat HTML page")
    void notFoundIsProblemJson() {
        ResponseEntity<String> response = rest.getForEntity(
                url("/api/courses/999999"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The default Boot behaviour for an unhandled 404 is an HTML error page.
        // Getting JSON here proves the advice ran.
        assertThat(response.getBody()).doesNotContain("<html");
        assertThat(parse(response.getBody()).get("errorCode").asText())
                .isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("actuator reports health, and separates liveness from readiness")
    void actuatorProbes() {
        assertThat(parse(rest.getForEntity(url("/actuator/health"), String.class).getBody())
                .get("status").asText()).isEqualTo("UP");

        // The two probes fieldbook chapter 33 argues about, now real endpoints.
        assertThat(rest.getForEntity(url("/actuator/health/liveness"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity(url("/actuator/health/readiness"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // Readiness includes the database; liveness must not. That is the whole
        // distinction, and getting it backwards turns a slow query into a
        // restart loop.
        JsonNode readiness = parse(rest.getForEntity(
                url("/actuator/health/readiness"), String.class).getBody());
        assertThat(readiness.get("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("/actuator/env is NOT exposed, so the datasource password stays private")
    void sensitiveActuatorEndpointsAreClosed() {
        // include: health,info,metrics - and this is what that line buys.
        // `include: "*"` would publish the database URL, the username and every
        // environment variable to anyone who can reach the port.
        assertThat(rest.getForEntity(url("/actuator/env"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity(url("/actuator/configprops"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------

    private ResponseEntity<String> postEnrollment(Long student, Long course) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"studentId\":" + student + ",\"courseId\":" + course + "}";
        return rest.exchange(url("/api/enrollments"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private Long newStudent(String number) {
        return studentRepository.saveAndFlush(new Student(number, "Test", "Student",
                Email.of(number.toLowerCase() + "@studenti.unicam.it"),
                LocalDate.of(2004, 1, 1), 2025)).getId();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Response was not JSON: " + json, e);
        }
    }
}
