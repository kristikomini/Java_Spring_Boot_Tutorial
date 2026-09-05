package it.unicam.cs.enrollment.spring.exception;

/**
 * A rule the domain refused, as opposed to a request that was malformed.
 *
 * <p>The distinction decides the status code, and it is the one juniors most
 * often get wrong. 400 means "I could not understand this request". 409 means
 * "I understood it perfectly and the current state of the world does not allow
 * it". Asking to enroll in a full course is a well-formed request about a real
 * course by a real student, so it is a 409 - and a client can usefully retry it
 * later, which is exactly why the distinction is worth making.
 *
 * <p>The static factory methods keep the error codes and the wording in one
 * place. Every one of these codes is byte-identical to the Jakarta EE version,
 * because a client should not be able to tell which implementation answered.
 * That is the actual test of whether these two applications are the same
 * service.
 */
public class BusinessRuleViolationException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public BusinessRuleViolationException(String errorCode, String message) {
        super(errorCode, message);
    }

    public static BusinessRuleViolationException courseFull(String courseCode, int capacity) {
        return new BusinessRuleViolationException(
                "COURSE_FULL",
                "Course " + courseCode + " has reached its capacity of " + capacity + " students");
    }

    public static BusinessRuleViolationException enrollmentWindowClosed(String courseCode) {
        return new BusinessRuleViolationException(
                "ENROLLMENT_WINDOW_CLOSED",
                "The enrollment window for course " + courseCode + " is not currently open");
    }

    public static BusinessRuleViolationException studentNotEligible(String studentNumber,
                                                                    String status) {
        return new BusinessRuleViolationException(
                "STUDENT_NOT_ELIGIBLE",
                "Student " + studentNumber + " cannot enroll while in status " + status);
    }

    public static BusinessRuleViolationException prerequisitesNotMet(String courseCode,
                                                                     String missingCourses) {
        return new BusinessRuleViolationException(
                "PREREQUISITES_NOT_MET",
                "Cannot enroll in " + courseCode
                        + ": the following prerequisites have not been passed: " + missingCourses);
    }

    public static BusinessRuleViolationException illegalStateTransition(String detail) {
        return new BusinessRuleViolationException("ILLEGAL_STATE_TRANSITION", detail);
    }

    public static BusinessRuleViolationException invalidGrade(String detail) {
        return new BusinessRuleViolationException("INVALID_GRADE", detail);
    }
}
