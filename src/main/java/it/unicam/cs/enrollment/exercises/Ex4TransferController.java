package it.unicam.cs.enrollment.exercises;

import it.unicam.cs.enrollment.domain.model.Enrollment;
import it.unicam.cs.enrollment.web.dto.EnrollmentResponse;
import it.unicam.cs.enrollment.web.mapper.EnrollmentMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * EXERCISE 4 - Exposing it over HTTP (the web layer)
 * =============================================================================
 * Difficulty: short, once Exercise 3 works. Depends on it.
 *
 * <p>Run the tests for this exercise with:
 * <pre>mvn test -Pexercises -Dtest=Ex4TransferControllerTest</pre>
 *
 * <h2>What to do</h2>
 * Implement {@link #transfer(TransferRequest)} so that
 * {@code POST /api/exercises/transfer} works end to end. Once both this and
 * Exercise 3 are done, the endpoint is live on your running application:
 *
 * <pre>
 * curl -X POST http://localhost:8280/enrollment/api/exercises/transfer \
 *      -H "Content-Type: application/json" \
 *      -d '{"studentId":102,"fromCourseId":52,"toCourseId":53}'
 * </pre>
 *
 * <h2>What you are practising</h2>
 * <ul>
 *   <li><strong>How little a controller does.</strong> Delegate, map, choose a
 *       status code. That is the entire job. If you find yourself writing an
 *       {@code if} about business rules here, it belongs in the service.</li>
 *   <li><strong>No try/catch.</strong> Let the service's exceptions propagate.
 *       {@code RestExceptionHandler} - a {@code @RestControllerAdvice} - already
 *       turns {@code ResourceNotFoundException} into 404 and
 *       {@code BusinessRuleViolationException} into 409. Catching them here
 *       would duplicate that and get it subtly wrong.</li>
 *   <li><strong>Declarative validation.</strong> {@code @Valid} on the
 *       parameter is already written. A malformed body is rejected before your
 *       code runs, and comes back as a 400 listing every bad field.</li>
 * </ul>
 *
 * <h2>Which status code?</h2>
 * Return <strong>200 OK</strong> with the new enrollment as the body. A case
 * could be made for 201 Created with a {@code Location} header, since a new
 * enrollment row really is created - look at {@code EnrollmentController.enroll}
 * for how that is built. 200 is chosen here because the caller's mental model is
 * "move this student", not "create a resource". Deciding this deliberately,
 * rather than by habit, is the actual exercise.
 *
 * <h2>Hint</h2>
 * The whole method is two lines:
 * <pre>
 * Enrollment moved = transferService.transfer(...);
 * return ResponseEntity.ok(enrollmentMapper.toResponse(moved));
 * </pre>
 */
@RestController
@RequestMapping(path = "/api/exercises",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class Ex4TransferController {

    private final Ex3TransferService transferService;
    private final EnrollmentMapper enrollmentMapper;

    public Ex4TransferController(Ex3TransferService transferService,
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
    @PostMapping(path = "/transfer", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EnrollmentResponse> transfer(@Valid @RequestBody TransferRequest request) {
        // TODO Exercise 4: delegate to transferService, map the result, return 200.
        throw new UnsupportedOperationException(
                "Exercise 4 not implemented yet - see the hint in the Javadoc above.");
    }

    /**
     * The request body. Provided complete - note that every field is validated,
     * so a bad request never reaches the handler method.
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
