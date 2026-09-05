package it.unicam.cs.enrollment.spring.web.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 * THE ERROR BODY - THE ONE CLASS THAT MUST NOT DIVERGE
 * ============================================================================
 * RFC 7807, "Problem Details for HTTP APIs". A field-for-field copy of
 * it.unicam.cs.enrollment.api.dto.response.ProblemDetail, and the copying is the
 * requirement rather than an accident.
 *
 * <p>A client calling this service must not be able to tell which
 * implementation answered. If the Spring version renamed {@code errorCode} to
 * {@code code}, or dropped {@code correlationId}, then these are two different
 * APIs that happen to share a URL - and the whole comparison stops being a
 * comparison. The test that proves it is CourseControllerTest, which asserts on
 * the JSON field names.
 *
 * <p>WHY NOT SPRING'S OWN {@code org.springframework.http.ProblemDetail}?
 * Spring 6 ships one, and in a greenfield project you should use it -
 * {@code ResponseEntity.of(problemDetail)} and {@code @ExceptionHandler}
 * returning it is idiomatic modern Spring. It is not used here for two reasons
 * worth stating. It serialises extension fields inside a nested
 * {@code properties} object rather than at the top level, so the JSON would
 * differ from the Jakarta EE version in exactly the way that matters. And
 * writing it out by hand shows what RFC 7807 actually is: seven ordinary fields,
 * not a framework feature. Knowing the shape is portable; knowing
 * {@code ProblemDetail.forStatusAndDetail} is not.
 *
 * <p>THE JSON-B / JACKSON DIFFERENCE. Over there, Yasson (JSON-B) omits null
 * fields by default. Jackson includes them, so without configuration the same
 * object would serialise with {@code "violations": null} and
 * {@code "instance": null} where the Jakarta EE version emits nothing at all.
 * That is fixed once, globally, in application.yml
 * ({@code default-property-inclusion: non_null}) rather than by annotating every
 * DTO - see the comment there. It is the clearest example in this module of two
 * specifications with different defaults producing two different wire formats
 * from one identical class.
 */
public class ProblemDetail {

    /** A URI identifying the problem TYPE. Stable; safe for a client to switch on. */
    private String type;

    /** A short human-readable summary. May be reworded without breaking anyone. */
    private String title;

    /** The HTTP status, repeated in the body so a logged response is self-contained. */
    private int status;

    /** What went wrong THIS time, with the specifics filled in. */
    private String detail;

    /** The path that produced it. */
    private String instance;

    /** The machine-readable code. This is the field a front end branches on. */
    private String errorCode;

    /**
     * The value that ties this response to the server logs.
     *
     * <p>Not part of RFC 7807 - the spec explicitly allows extension members, and
     * this is the most useful one there is. A user can paste it into a support
     * ticket and an engineer can grep for it. See CorrelationIdFilter.
     */
    private String correlationId;

    private Instant timestamp;

    /** Populated only for validation failures; null otherwise, and then omitted. */
    private List<Violation> violations;

    public ProblemDetail() {
        this.timestamp = Instant.now();
    }

    public ProblemDetail(String type, String title, int status, String detail) {
        this();
        this.type = type;
        this.title = title;
        this.status = status;
        this.detail = detail;
    }

    /**
     * One rejected field. Returning a LIST of these, rather than failing on the
     * first, is a deliberate API decision: a form with three bad fields should
     * light up all three at once, not make the user submit three times.
     */
    public static class Violation {

        private String field;
        private String message;
        private Object rejectedValue;

        public Violation() {
        }

        public Violation(String field, String message, Object rejectedValue) {
            this.field = field;
            this.message = message;
            this.rejectedValue = rejectedValue;
        }

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Object getRejectedValue() {
            return rejectedValue;
        }

        public void setRejectedValue(Object rejectedValue) {
            this.rejectedValue = rejectedValue;
        }
    }

    public void addViolation(String field, String message, Object rejectedValue) {
        if (violations == null) {
            violations = new ArrayList<>();
        }
        violations.add(new Violation(field, message, rejectedValue));
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public List<Violation> getViolations() {
        return violations;
    }

    public void setViolations(List<Violation> violations) {
        this.violations = violations;
    }
}
