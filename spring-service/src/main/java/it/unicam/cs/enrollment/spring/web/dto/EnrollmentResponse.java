package it.unicam.cs.enrollment.spring.web.dto;

import java.time.Instant;

/**
 * One enrollment on the wire. Same field names as the Jakarta EE version, so a
 * client cannot tell the two services apart.
 *
 * <p>Notice how much this DTO flattens. The entity has a Student object and a
 * Course object, each with associations of their own; the response has
 * studentNumber, studentName, courseCode, courseTitle and courseCredits as
 * plain values. That flattening is the entire justification for having a DTO
 * layer at all, and fieldbook chapter 13 makes the argument in full:
 *
 * <p>Serialising the entity directly would drag the whole object graph through
 * Jackson - student, then their other enrollments, then those courses - either
 * looping forever or exploding on a lazy proxy outside the transaction. It
 * would also publish the database shape as the API contract, so renaming a
 * column would break every client.
 *
 * <p>formattedGrade is included next to grade on purpose. The number is for a
 * client that wants to compute; the string ("30 e lode") is for one that wants
 * to display, and the rule about how honours are written down belongs in the
 * domain rather than in every front end that consumes this.
 */
public record EnrollmentResponse(
        Long id,
        Long studentId,
        String studentNumber,
        String studentName,
        Long courseId,
        String courseCode,
        String courseTitle,
        int courseCredits,
        String status,
        Instant enrolledAt,
        Instant completedAt,
        Integer grade,
        boolean withHonours,
        String formattedGrade) {
}
