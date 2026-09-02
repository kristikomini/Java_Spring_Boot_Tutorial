package it.unicam.cs.enrollment.exercises;

import it.unicam.cs.enrollment.api.dto.response.EnrollmentResponse;
import it.unicam.cs.enrollment.api.mapper.EnrollmentMapper;
import it.unicam.cs.enrollment.domain.model.Enrollment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * EXERCISE 4 - Exposing it over HTTP (the REST layer)
 * =============================================================================
 * Difficulty: short, once Exercise 3 works. Depends on it.
 *
 * <p>Run the tests for this exercise with:
 * <pre>mvn test -Pexercises -Dtest=Ex4TransferResourceTest</pre>
 *
 * <h2>What to do</h2>
 * Implement {@link #transfer(TransferRequest)} so that
 * {@code POST /api/exercises/transfer} works end to end. Once both this and
 * Exercise 3 are done, the endpoint is live on your running server:
 *
 * <pre>
 * curl -X POST http://localhost:8280/enrollment/api/exercises/transfer \
 *      -H "Content-Type: application/json" \
 *      -d '{"studentId":102,"fromCourseId":52,"toCourseId":53}'
 * </pre>
 *
 * <h2>What you are practising</h2>
 * <ul>
 *   <li><strong>How little a resource does.</strong> Delegate, map, choose a
 *       status code. That is the entire job. If you find yourself writing an
 *       {@code if} about business rules here, it belongs in the service.</li>
 *   <li><strong>No try/catch.</strong> Let the service's exceptions propagate.
 *       The mappers in {@code api/exception} already turn
 *       {@code ResourceNotFoundException} into 404 and
 *       {@code BusinessRuleViolationException} into 409. Catching them here
 *       would duplicate that and get it subtly wrong.</li>
 *   <li><strong>Declarative validation.</strong> {@code @Valid @NotNull} on the
 *       parameter is already written. A malformed body is rejected before your
 *       code runs, and comes back as a 400 listing every bad field.</li>
 * </ul>
 *
 * <h2>Which status code?</h2>
 * Return <strong>200 OK</strong> with the new enrollment as the body. A case
 * could be made for 201 Created with a {@code Location} header, since a new
 * enrollment row really is created - look at {@code EnrollmentResource.enroll}
 * for how that is built. 200 is chosen here because the caller's mental model is
 * "move this student", not "create a resource". Deciding this deliberately,
 * rather than by habit, is the actual exercise.
 *
 * <h2>Hint</h2>
 * The whole method is three lines:
 * <pre>
 * Enrollment moved = transferService.transfer(...);
 * return Response.ok(enrollmentMapper.toResponse(moved)).build();
 * </pre>
 */
@Path("/exercises")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class Ex4TransferResource {

    @Inject
    Ex3TransferService transferService;

    @Inject
    EnrollmentMapper enrollmentMapper;

    /** CDI needs a no-arg constructor, and RESTEasy needs it to be public. */
    public Ex4TransferResource() {
    }

    /** Constructor injection, used by the tests. */
    public Ex4TransferResource(Ex3TransferService transferService,
                               EnrollmentMapper enrollmentMapper) {
        this.transferService = transferService;
        this.enrollmentMapper = enrollmentMapper;
    }

    /**
     * Moves a student between two courses.
     *
     * @param request the transfer to perform
     * @return 200 with the new {@link EnrollmentResponse}
     */
    @POST
    @Path("/transfer")
    public Response transfer(@Valid @NotNull TransferRequest request) {
        // TODO Exercise 4: delegate to transferService, map the result, return 200.
        throw new UnsupportedOperationException(
                "Exercise 4 not implemented yet - see the hint in the Javadoc above.");
    }

    /**
     * The request body. Provided complete - note that every field is validated,
     * so a bad request never reaches the resource method.
     */
    public static class TransferRequest {

        @NotNull(message = "studentId is required")
        @Positive(message = "studentId must be a positive number")
        private Long studentId;

        @NotNull(message = "fromCourseId is required")
        @Positive(message = "fromCourseId must be a positive number")
        private Long fromCourseId;

        @NotNull(message = "toCourseId is required")
        @Positive(message = "toCourseId must be a positive number")
        private Long toCourseId;

        public TransferRequest() {
        }

        public TransferRequest(Long studentId, Long fromCourseId, Long toCourseId) {
            this.studentId = studentId;
            this.fromCourseId = fromCourseId;
            this.toCourseId = toCourseId;
        }

        public Long getStudentId() {
            return studentId;
        }

        public void setStudentId(Long studentId) {
            this.studentId = studentId;
        }

        public Long getFromCourseId() {
            return fromCourseId;
        }

        public void setFromCourseId(Long fromCourseId) {
            this.fromCourseId = fromCourseId;
        }

        public Long getToCourseId() {
            return toCourseId;
        }

        public void setToCourseId(Long toCourseId) {
            this.toCourseId = toCourseId;
        }
    }
}
