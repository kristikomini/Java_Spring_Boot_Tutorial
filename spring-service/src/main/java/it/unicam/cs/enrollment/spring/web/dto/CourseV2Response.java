package it.unicam.cs.enrollment.spring.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * VERSION 2 of the course representation.
 *
 * <p>This DTO exists to make one point concretely: you version an API when, and
 * only when, you must make a change that would break a client that is already
 * working. Two changes here qualify.
 *
 * <p>1. professorId and professorName are replaced by a NESTED professor
 * object. A client reading response.professorName gets null - it does not get an
 * error, it gets a page with a blank name, which is the worst kind of breakage
 * because nothing alerts anybody.
 *
 * <p>2. occupiedSeats is added alongside availableSeats. ADDING a field is NOT
 * breaking, and that is the more useful half of the lesson: a well-written
 * client ignores fields it does not know (the tolerant reader), so additive
 * change needs no new version at all. Most teams version far too eagerly
 * because they never learned this distinction.
 *
 * <p>WHAT IS BREAKING, roughly in order of how often people get it wrong:
 * removing a field, renaming a field, changing a type (number to string),
 * narrowing what you accept, adding a required request field, and changing a
 * status code. What is not: adding an optional response field, adding an
 * optional request field with a default, adding a whole new endpoint.
 *
 * <p>THREE WAYS TO CARRY THE VERSION, and you will be asked to compare them:
 *
 * <p>URI - /api/v2/courses. What this project does. Ugly to purists because the
 * URI should identify the resource rather than its representation, and it is
 * what nearly everyone ships, because it is visible in a log, a browser address
 * bar and a curl command with no tooling at all.
 *
 * <p>HEADER - a custom X-API-Version. Keeps URIs clean and makes the version
 * invisible in every log and bug report you will ever read.
 *
 * <p>MEDIA TYPE - Accept: application/vnd.unicam.course.v2+json. The most
 * correct by the letter of HTTP, and the one clients get wrong most often.
 *
 * <p>The real answer to "which is best" is that the hard part is not the
 * mechanism, it is deciding how long v1 stays alive and how you find out who is
 * still calling it. See the Deprecation and Sunset headers in CourseV2Controller.
 */
public record CourseV2Response(
        Long id,
        String code,
        String title,
        String description,
        int credits,
        int capacity,
        long availableSeats,

        /** Added in v2. Additive, so not itself a breaking change. */
        long occupiedSeats,

        String semester,
        int academicYear,

        /** Replaces the flat professorId and professorName. This IS breaking. */
        ProfessorSummary professor,

        Instant enrollmentOpensAt,
        Instant enrollmentClosesAt,
        boolean enrollmentOpen,
        List<String> prerequisiteCodes) {

    /**
     * A nested record. Worth the change because the flat version could only ever
     * carry two fields before the names became silly - professorDepartment,
     * professorEmail, professorTitle - and because a client that wants to render
     * a professor card now has an object to hand to a component rather than five
     * loose strings it has to reassemble.
     */
    public record ProfessorSummary(
            Long id,
            String staffNumber,
            String fullName,
            String title,
            String department) {
    }
}
