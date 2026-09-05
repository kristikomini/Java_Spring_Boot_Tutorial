package it.unicam.cs.enrollment.spring.web.mapper;

import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.web.dto.CourseResponse;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;

/**
 * Entity to DTO. Hand-written, exactly like its Jakarta EE counterpart.
 *
 * <p>Most Spring codebases you join will use MapStruct here - an annotation
 * processor that GENERATES this class at compile time from an interface. It is
 * worth knowing why teams reach for it and why this module does not.
 *
 * <p>For it: mapping code is the most boring code in any application, and
 * MapStruct produces plain Java you can read in target/generated-sources, with
 * no reflection cost. Against it: the interesting part of a mapper is never the
 * fields that match by name, it is the three that do not - and here those are
 * availableSeats (computed from a separate query), enrollmentOpen (needs the
 * clock) and professorName (a method on an association). A generator handles the
 * boring 90% and you write the other 10% by hand anyway.
 *
 * <p>Being able to say that is worth more than a preference. Backlog item B7
 * notes MapStruct and Lombok as a gap in the fieldbook; this comment is the
 * placeholder for that chapter.
 */
@Component
public class CourseMapper {

    private final Clock clock;

    public CourseMapper(Clock clock) {
        this.clock = clock;
    }

    /**
     * The list projection: no prerequisites.
     *
     * <p>Not laziness - reading course.getPrerequisites() here would trigger a
     * lazy load PER COURSE, turning a page of 20 into 21 queries. The detail
     * endpoint pays for them with an explicit LEFT JOIN FETCH; the list endpoint
     * chooses not to need them. Which fields a projection includes is a
     * performance decision as much as a design one.
     */
    public CourseResponse toSummary(Course course, long occupiedSeats) {
        return new CourseResponse(
                course.getId(),
                course.getCode(),
                course.getTitle(),
                course.getDescription(),
                course.getCredits(),
                course.getCapacity(),
                Math.max(0, course.getCapacity() - occupiedSeats),
                course.getSemester().name(),
                course.getAcademicYear(),
                course.getProfessor().getId(),
                course.getProfessor().fullName(),
                course.getEnrollmentOpensAt(),
                course.getEnrollmentClosesAt(),
                course.isEnrollmentOpen(clock.instant()),
                null);
    }

    /**
     * The detail projection. Safe to touch prerequisites here ONLY because the
     * caller used findByIdWithPrerequisites, which fetched them in the same
     * query. Call this with a course loaded any other way and you get either an
     * extra query or, outside a transaction, a LazyInitializationException.
     */
    public CourseResponse toDetail(Course course, long occupiedSeats) {
        CourseResponse summary = toSummary(course, occupiedSeats);
        List<String> prerequisiteCodes = course.getPrerequisites().stream()
                .map(Course::getCode)
                .sorted(Comparator.naturalOrder())
                .toList();

        // Records are immutable, so "add a field" means "build a new one". The
        // canonical constructor makes that a compile-time-checked copy: add a
        // component to CourseResponse and this line stops compiling, which is
        // exactly what you want from a mapper.
        return new CourseResponse(
                summary.id(), summary.code(), summary.title(), summary.description(),
                summary.credits(), summary.capacity(), summary.availableSeats(),
                summary.semester(), summary.academicYear(),
                summary.professorId(), summary.professorName(),
                summary.enrollmentOpensAt(), summary.enrollmentClosesAt(),
                summary.enrollmentOpen(),
                prerequisiteCodes);
    }
}
