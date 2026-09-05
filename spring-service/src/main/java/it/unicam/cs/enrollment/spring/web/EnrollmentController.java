package it.unicam.cs.enrollment.spring.web;

import it.unicam.cs.enrollment.spring.domain.Enrollment;
import it.unicam.cs.enrollment.spring.service.EnrollmentService;
import it.unicam.cs.enrollment.spring.web.dto.EnrollRequest;
import it.unicam.cs.enrollment.spring.web.dto.EnrollmentResponse;
import it.unicam.cs.enrollment.spring.web.mapper.EnrollmentMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * The write endpoint - the one that exercises the seat-counting rule, the
 * pessimistic lock and the unique constraint all at once.
 *
 * <p>Notice how little is here. Fieldbook chapter 12 calls a resource "a thin
 * translator", and this class is the proof: it converts a JSON body into two
 * Long arguments, calls one service method, and converts the result into a
 * response with a Location header. Every rule about whether the enrollment is
 * allowed lives in the service and the domain, where it can be unit-tested with
 * no HTTP at all.
 *
 * <p>A controller that grows an {@code if} about business state is a controller
 * that has started stealing the service layer job, and the symptom is always the
 * same: the rule cannot be reused by the scheduled job, or tested without
 * MockMvc.
 */
@RestController
@RequestMapping({"/api/enrollments", "/api/v1/enrollments"})
@Tag(name = "Enrollments", description = "Enrolling, withdrawing, grading")
public class EnrollmentController {

    private final EnrollmentService enrollmentService;
    private final EnrollmentMapper enrollmentMapper;

    public EnrollmentController(EnrollmentService enrollmentService,
                                EnrollmentMapper enrollmentMapper) {
        this.enrollmentService = enrollmentService;
        this.enrollmentMapper = enrollmentMapper;
    }

    /**
     * POST /api/enrollments
     *
     * <p>201 Created with a Location header, not 200. That is the HTTP
     * specification rather than a convention: a request that created a new
     * resource should say where it now lives, and a client can follow the header
     * instead of guessing the URL. Fieldbook chapter 13 has the full table of
     * which status to choose.
     *
     * <p>WHERE @Valid RUNS. Here, on the controller parameter - before a single
     * line of service code executes, so the service never sees a null courseId.
     * The entity ALSO carries constraints, and Hibernate re-checks them at flush
     * time. That duplication is deliberate and is chapter 13 again: the
     * controller check produces a good error message for a human, and the entity
     * check is the one that cannot be bypassed by a different entry point - a
     * scheduled job, a data import, another service.
     *
     * <p>{@code @Valid} on the body triggers MethodArgumentNotValidException on
     * failure, which is a DIFFERENT exception from the ConstraintViolationException
     * that {@code @Validated} on a query parameter produces. RestExceptionHandler
     * has to handle both, and forgetting one is the usual reason a project has
     * two different-looking validation error formats.
     */
    @PostMapping
    public ResponseEntity<EnrollmentResponse> enroll(@Valid @RequestBody EnrollRequest request) {
        Enrollment enrollment = enrollmentService.enroll(request.studentId(), request.courseId());

        // The Spring answer to @Context UriInfo. It reads the CURRENT request, so
        // the URI it builds respects the host and scheme the client actually used
        // - which matters the moment there is a reverse proxy in front, and is
        // why hardcoding "http://localhost:8281" here would break in production.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(enrollment.getId())
                .toUri();

        return ResponseEntity.created(location)
                .body(enrollmentMapper.toResponse(enrollment));
    }

    @GetMapping("/{id}")
    public EnrollmentResponse findById(@PathVariable("id") Long id) {
        return enrollmentMapper.toResponse(enrollmentService.findById(id));
    }

    /**
     * DELETE would be wrong here, and it is worth saying why.
     *
     * <p>Withdrawing is not deleting: the row survives with status WITHDRAWN,
     * because the fact that a student once enrolled and withdrew is history the
     * registrar needs. POST to a named sub-resource says what actually happens.
     *
     * <p>It is also not idempotent, and the API should not pretend otherwise -
     * withdrawing an already-withdrawn enrollment is an illegal state transition
     * and returns 409, rather than silently succeeding. Fieldbook chapter 13 has
     * the safe/idempotent table; this is the case where the honest answer is
     * "neither".
     */
    @PostMapping("/{id}/withdrawal")
    public EnrollmentResponse withdraw(@PathVariable("id") Long id) {
        return enrollmentMapper.toResponse(enrollmentService.withdraw(id));
    }
}
