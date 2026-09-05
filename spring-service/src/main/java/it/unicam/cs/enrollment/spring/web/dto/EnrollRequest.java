package it.unicam.cs.enrollment.spring.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * The body of POST /api/enrollments.
 *
 * <p>The constraints are jakarta.validation - byte for byte the same annotations
 * as the Jakarta EE request DTO, because Bean Validation is a specification and
 * Spring implements it with the same Hibernate Validator that WildFly ships.
 * Only the trigger differs: over there the container validates a JAX-RS
 * parameter annotated @Valid; here Spring MVC does it, and only if
 * spring-boot-starter-validation is on the classpath. Leave that dependency out
 * and this file compiles, deploys and validates nothing.
 *
 * <p>Constraints on a RECORD component apply to the field, the constructor
 * parameter and the accessor at once, so this works with no extra ceremony.
 *
 * <p>WHY THE REQUEST DTO IS NOT THE ENTITY. If this took a full Enrollment, a
 * client could post a status of COMPLETED and a grade of 30 and award itself a
 * degree. The request object exists to state exactly which two values the caller
 * is allowed to choose, and mass assignment is the vulnerability class that
 * arises when a codebase skips this step.
 *
 * <p>@Positive as well as @NotNull is not belt-and-braces: an id of -1 would
 * otherwise reach the repository, miss, and come back as a 404, which is a
 * misleading answer to a malformed question. 400 is the honest status.
 */
public record EnrollRequest(

        @NotNull(message = "studentId is required")
        @Positive(message = "studentId must be a positive number")
        Long studentId,

        @NotNull(message = "courseId is required")
        @Positive(message = "courseId must be a positive number")
        Long courseId) {
}
