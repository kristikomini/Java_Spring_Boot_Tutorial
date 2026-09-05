package it.unicam.cs.enrollment.spring.web.error;

import it.unicam.cs.enrollment.spring.exception.BusinessRuleViolationException;
import it.unicam.cs.enrollment.spring.exception.DuplicateResourceException;
import it.unicam.cs.enrollment.spring.exception.ResourceNotFoundException;
import it.unicam.cs.enrollment.spring.web.dto.ProblemDetail;
import it.unicam.cs.enrollment.spring.web.filter.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * ============================================================================
 * SIX FILES, BECOME ONE
 * ============================================================================
 * The Jakarta EE application has six classes in
 * it.unicam.cs.enrollment.api.exception, each implementing
 * {@code ExceptionMapper<SomeException>} and each annotated {@code @Provider}.
 * The container discovers them by scanning and dispatches on the type parameter.
 *
 * <p>Spring puts all six in one class. {@code @RestControllerAdvice} registers
 * this bean as an error handler for EVERY controller, and
 * {@code @ExceptionHandler} dispatches on the parameter type - the same
 * mechanism, expressed as methods rather than classes.
 *
 * <p>WHICH IS BETTER is a real question with a real answer, and it is worth
 * having one ready. Six files means each is trivially testable and you can add a
 * seventh without touching existing code. One file means the whole error
 * contract of the API is visible on one screen, which is what you actually want
 * when someone asks "what does this service return when X fails?". Most teams
 * find the second more valuable, and split it once it passes a few hundred
 * lines - typically one advice per bounded context.
 *
 * <p>DISPATCH IS BY MOST-SPECIFIC TYPE in both. A handler for
 * BusinessRuleViolationException wins over one for its parent BusinessException,
 * which wins over one for Exception. The catch-all at the bottom is therefore
 * genuinely last, and the ordering is not something you control by method order.
 *
 * <p>THE STATUS CODES AND ERROR CODES BELOW ARE THE CONTRACT, and they match the
 * Jakarta EE mappers exactly. A client must not be able to tell which
 * implementation answered.
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    private static final String TYPE_BASE = "https://api.unicam.it/problems/";

    /** 404. The resource genuinely is not there. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException ex,
                                                        HttpServletRequest request) {
        log.debug("Resource not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "resource-not-found", "Resource Not Found",
                ex.getMessage(), ex.getErrorCode(), request);
    }

    /**
     * 409. The request was understood and the current state forbids it.
     *
     * <p>Logged at INFO, not ERROR. This is the application working correctly -
     * a full course refusing a student is the system doing its job. Logging it
     * at ERROR is how teams end up with alerting nobody reads.
     */
    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<ProblemDetail> handleBusinessRule(BusinessRuleViolationException ex,
                                                            HttpServletRequest request) {
        log.info("Business rule violation [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return build(HttpStatus.CONFLICT, "business-rule-violation", "Business Rule Violation",
                ex.getMessage(), ex.getErrorCode(), request);
    }

    /** 409. Already exists. */
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ProblemDetail> handleDuplicate(DuplicateResourceException ex,
                                                         HttpServletRequest request) {
        log.info("Duplicate resource: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "duplicate-resource", "Duplicate Resource",
                ex.getMessage(), ex.getErrorCode(), request);
    }

    /**
     * 400 for a body that failed {@code @Valid}.
     *
     * <p>THIS IS THE FIRST OF TWO VALIDATION HANDLERS, and having to write both
     * is one of the genuine rough edges of Spring MVC. A rejected request BODY
     * raises MethodArgumentNotValidException; a rejected query PARAMETER on a
     * {@code @Validated} controller raises ConstraintViolationException. Same
     * annotations, same validator, two exception types, because one goes through
     * data binding and the other through an AOP proxy.
     *
     * <p>Handle only one and half your validation errors come back as a 500 with
     * an HTML error page. It is a common bug and it is invisible until someone
     * sends a bad query string.
     *
     * <p>The Jakarta EE application does not have this problem: JAX-RS raises
     * ConstraintViolationException for both, and one mapper covers it.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        ProblemDetail problem = create(HttpStatus.BAD_REQUEST, "validation-failed",
                "Validation Failed",
                "The request contains " + ex.getBindingResult().getErrorCount()
                        + " invalid field(s)",
                "VALIDATION_FAILED", request);

        ex.getBindingResult().getFieldErrors().forEach(error ->
                problem.addViolation(error.getField(), error.getDefaultMessage(),
                        safeRejectedValue(error.getRejectedValue())));

        log.debug("Validation failed with {} violation(s)", ex.getBindingResult().getErrorCount());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(problem);
    }

    /** 400 for a rejected query parameter. The second half of the pair above. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleParamValidation(ConstraintViolationException ex,
                                                               HttpServletRequest request) {
        ProblemDetail problem = create(HttpStatus.BAD_REQUEST, "validation-failed",
                "Validation Failed",
                "The request contains " + ex.getConstraintViolations().size()
                        + " invalid field(s)",
                "VALIDATION_FAILED", request);

        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            problem.addViolation(lastNodeOf(violation.getPropertyPath()),
                    violation.getMessage(),
                    safeRejectedValue(violation.getInvalidValue()));
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(problem);
    }

    /**
     * 400 for a malformed value the controller rejected by hand, and for a path
     * variable that will not convert (GET /api/courses/abc).
     */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception ex,
                                                          HttpServletRequest request) {
        String detail = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? "Parameter '" + mismatch.getName() + "' has an invalid value"
                : ex.getMessage();

        // Note what is NOT in that message for the mismatch case: the target Java
        // type and the conversion exception. The default Spring message includes
        // both, which tells an attacker your class names for no benefit to a
        // legitimate caller.
        return build(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid Request",
                detail, "INVALID_REQUEST", request);
    }

    /**
     * 409 for a constraint the database enforced that the service did not catch
     * first.
     *
     * <p>This is the unique constraint on (student_id, course_id) firing in the
     * race the pessimistic lock is meant to prevent - or in a deployment where
     * two instances raced past it. The service checks for a duplicate and returns
     * a friendly message; THIS handler is what happens when the check passed and
     * the insert still lost. It is the safety net behind the safety net.
     *
     * <p>DataIntegrityViolationException is a SPRING exception, not a Hibernate
     * or JDBC one. That translation is what {@code @Repository} buys you - see
     * StudentRepository - and it is why this handler does not import anything
     * from org.hibernate.
     *
     * <p>The exception message is deliberately NOT passed through. It contains
     * the SQL, the constraint name and often the parameter values, and all three
     * belong in the log rather than in an HTTP response.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        log.warn("Database rejected the write: {}", ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "duplicate-resource", "Duplicate Resource",
                "The operation conflicts with existing data",
                "DUPLICATE_RESOURCE", request);
    }

    /**
     * 409 for a lost optimistic-lock race - two users edited the same row and the
     * second one is being told to reload and try again.
     *
     * <p>Spring wraps the JPA OptimisticLockException in its own
     * ObjectOptimisticLockingFailureException, so catching the JPA type alone
     * would miss it. That is the exception-translation layer being helpful and
     * surprising in the same move, and it is worth knowing before you spend an
     * afternoon on it.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        log.info("Optimistic lock conflict: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "concurrent-modification", "Concurrent Modification",
                "This record was modified by someone else. Reload it and try again.",
                "OPTIMISTIC_LOCK_CONFLICT", request);
    }

    /**
     * 404 for a path that matches no handler at all.
     *
     * <p>THIS HANDLER EXISTS BECAUSE ITS ABSENCE WAS A BUG, and the bug is worth
     * describing because it is a trap built into the {@code @ExceptionHandler}
     * design.
     *
     * <p>When Spring cannot route a request it throws
     * {@code NoResourceFoundException} (Spring 6.1+; {@code NoHandlerFoundException}
     * before that). Those are Exceptions. The catch-all below handles
     * {@code Exception}. So without this method, every request to a URL that does
     * not exist - a typo, a scanner, a client on a stale path - was answered with
     * <b>500 Internal Server Error</b> and logged at ERROR with a stack trace.
     *
     * <p>Three things follow, and all three are real. The client is told the
     * server is broken when the client is the one that is wrong. The error log
     * fills with stack traces from anyone port-scanning you, burying the failures
     * that matter. And any alerting on 5xx rates fires on traffic you cannot
     * control.
     *
     * <p>It was caught by a test asserting that {@code /actuator/env} returns 404,
     * which was written to check that the actuator exposure list is closed - not
     * to check the error handler at all. That is the argument for asserting on
     * status codes rather than just on bodies: this assertion was about security
     * and it found a correctness bug.
     *
     * <p>THE GENERAL RULE: a catch-all {@code @ExceptionHandler(Exception.class)}
     * silently captures the framework own control-flow exceptions. Anything
     * Spring throws to mean something specific - unroutable path, unsupported
     * method, unreadable body, missing parameter - needs its own handler, or the
     * catch-all turns a precise 4xx into a misleading 5xx. Spring offers
     * {@code ResponseEntityExceptionHandler} as a base class that pre-declares
     * all of them; extending it is the other way to fix this, and would have
     * prevented the bug outright.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ProblemDetail> handleNoHandler(Exception ex,
                                                         HttpServletRequest request) {
        // DEBUG, not ERROR. An unknown path is not an application failure, and
        // logging it at ERROR is how a scanner fills your alerting.
        log.debug("No handler for {} {}", request.getMethod(), request.getRequestURI());

        return build(HttpStatus.NOT_FOUND, "resource-not-found", "Resource Not Found",
                "No endpoint " + request.getMethod() + " " + request.getRequestURI(),
                ResourceNotFoundException.ERROR_CODE, request);
    }

    /**
     * 405, for the right path with the wrong verb.
     *
     * <p>The same class of mistake as above: without this, POST to a GET-only
     * endpoint is a 500. 405 is the honest answer, and the {@code Allow} header
     * telling the client what IS permitted is required by the HTTP
     * specification - one of the most commonly omitted required headers there is.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        ProblemDetail problem = create(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed",
                "Method Not Allowed",
                "This endpoint does not support " + ex.getMethod(),
                "METHOD_NOT_ALLOWED", request);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.APPLICATION_JSON);
        if (ex.getSupportedHttpMethods() != null) {
            builder.allow(ex.getSupportedHttpMethods().toArray(new HttpMethod[0]));
        }
        return builder.body(problem);
    }

    /**
     * 400 for a body Jackson could not parse.
     *
     * <p>Malformed JSON, a string where a number was expected, an empty body on a
     * {@code @RequestBody} method. Again a 500 without this handler.
     *
     * <p>The parser message is NOT passed through: it quotes the offending input
     * and names the target Java class, so it is both an information leak and a
     * way to echo attacker-controlled text into a response.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                              HttpServletRequest request) {
        log.debug("Unreadable request body: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid Request",
                "The request body could not be parsed as JSON",
                "INVALID_REQUEST", request);
    }

    /**
     * 400 for a required query parameter that was not sent.
     *
     * <p>The fourth and last of the framework exceptions this API can provoke.
     * Naming the parameter is the difference between an error a caller can fix
     * and one they have to guess at.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid Request",
                "Required parameter '" + ex.getParameterName() + "' is missing",
                "INVALID_REQUEST", request);
    }

    /**
     * 500, the catch-all - and the most security-relevant method in the file.
     *
     * <p>The stack trace goes to the LOG. The response gets a generic sentence
     * and the correlation id. That asymmetry is the whole point: an engineer can
     * find the trace in seconds by grepping the id, and an attacker learns
     * nothing about the framework, the ORM, the database, or which of them
     * failed. Fieldbook chapter 15 counts a leaked stack trace as a genuine
     * finding, not a style issue - it names your library versions, and library
     * versions have published CVEs.
     *
     * <p>Boot needs one more thing for this to hold in every case:
     * {@code server.error.include-stacktrace: never} in application.yml, because
     * some failures never reach this handler at all - see the comment there.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex,
                                                          HttpServletRequest request) {
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        log.error("Unhandled exception [correlationId={}] on {} {}",
                correlationId, request.getMethod(), request.getRequestURI(), ex);

        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal Server Error",
                "An unexpected error occurred. Quote the correlation id when reporting it.",
                "INTERNAL_ERROR", request);
    }

    // ------------------------------------------------------------------
    // The equivalent of the Jakarta EE ProblemDetails helper class.
    // ------------------------------------------------------------------

    private ResponseEntity<ProblemDetail> build(HttpStatus status, String typeSlug, String title,
                                                String detail, String errorCode,
                                                HttpServletRequest request) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(create(status, typeSlug, title, detail, errorCode, request));
    }

    private ProblemDetail create(HttpStatus status, String typeSlug, String title,
                                 String detail, String errorCode, HttpServletRequest request) {
        ProblemDetail problem = new ProblemDetail(
                TYPE_BASE + typeSlug, title, status.value(), detail);
        problem.setErrorCode(errorCode);
        if (request != null) {
            problem.setInstance(request.getRequestURI());
        }
        problem.setCorrelationId(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY));
        return problem;
    }

    /** Bean Validation property paths look like "list.arg0.studentId"; we want the leaf. */
    private String lastNodeOf(Path propertyPath) {
        String last = null;
        for (Path.Node node : propertyPath) {
            if (node.getName() != null) {
                last = node.getName();
            }
        }
        return last == null ? "request" : last;
    }

    /**
     * Echo back what was rejected, truncated.
     *
     * <p>Truncated because the rejected value is attacker-controlled and
     * unbounded: a megabyte of text in one field would otherwise be copied into
     * the error response and the log. Echoing input at all is a small risk
     * accepted for a real usability gain, and 100 characters is where the trade
     * lands.
     */
    private Object safeRejectedValue(Object invalidValue) {
        if (invalidValue == null) {
            return null;
        }
        if (invalidValue instanceof Number || invalidValue instanceof Boolean) {
            return invalidValue;
        }
        String asString = String.valueOf(invalidValue);
        return asString.length() > 100 ? asString.substring(0, 100) + "..." : asString;
    }
}
