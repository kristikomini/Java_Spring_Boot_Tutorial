package it.unicam.cs.enrollment.spring.web.mapper;

import it.unicam.cs.enrollment.spring.domain.Enrollment;
import it.unicam.cs.enrollment.spring.web.dto.EnrollmentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * ============================================================================
 * THE MAPPER YOU DO NOT WRITE - AND THE ONE YOU STILL DO
 * ============================================================================
 * Compare this interface with {@link CourseMapper}, which is hand-written on
 * purpose. Both are in the codebase so the trade can be seen rather than argued
 * about.
 *
 * <p>MapStruct is an ANNOTATION PROCESSOR. At compile time it generates
 * {@code EnrollmentMapperImpl} in target/generated-sources/annotations - plain
 * Java, the same field assignments you would have typed, no reflection and no
 * proxy. Open that file once. It is the fastest cure for thinking of MapStruct
 * as magic, and it is what you show an interviewer who asks whether it is slow.
 *
 * <p>WHAT IT SAVED. Fourteen constructor arguments in a fixed order, of which
 * eleven are a mechanical walk down an association. Getting argument nine and
 * ten the wrong way round in a hand-written version compiles perfectly when both
 * are Strings, and puts the course title in the student name field.
 *
 * <p>WHAT IT DID NOT SAVE. The three lines below that use {@code expression}.
 * {@code fullName()} and {@code formattedGrade()} are not JavaBean getters, so
 * no generator can find them by name, and the whole interesting part of the
 * mapping had to be written by hand anyway. That is the honest summary of
 * MapStruct: it removes the boring 80% and leaves the 20% that needed a
 * decision.
 *
 * <p>THE SETTING THAT MAKES IT SAFE is in pom.xml, not here:
 * {@code unmappedTargetPolicy=ERROR}. Without it, adding a component to
 * EnrollmentResponse and forgetting to map it is a warning nobody reads, and the
 * field is silently null in every response forever. With it, the build fails and
 * names the field. Any project adopting MapStruct should turn this on in the
 * first commit; retrofitting it later means fixing a hundred warnings at once.
 *
 * <p>WHY {@code componentModel = "spring"}: it makes the generated class a
 * {@code @Component}, so it is injected like any other bean. It is set globally
 * in pom.xml via {@code -Amapstruct.defaultComponentModel=spring}, which is why
 * it does not appear in the annotation below.
 *
 * <p>AND LOMBOK, the other name that always appears beside this one. It is a
 * different tool for a different job - it generates getters, setters, builders
 * and constructors ON your classes, where MapStruct generates code BETWEEN them.
 * This project uses neither for entities, because Lombok-generated
 * {@code @Data} on a JPA entity produces an {@code equals} over every field
 * including lazy associations, which is the equals/hashCode trap in BaseEntity
 * in its most damaging form. Records cover most of what Lombok was invented for
 * and are part of the language.
 */
@Mapper
public interface EnrollmentMapper {

    /**
     * Each {@code source} is a path through the object graph, and every one of
     * them dereferences a LAZY association. Safe only because the repository
     * queries that produce these entities all JOIN FETCH both - see
     * EnrollmentRepository. Point this at an entity loaded by a plain findById
     * and it throws LazyInitializationException inside generated code, which is
     * an unpleasant place to debug.
     */
    @Mapping(target = "studentId", source = "student.id")
    @Mapping(target = "studentNumber", source = "student.studentNumber")
    @Mapping(target = "studentName", expression = "java(enrollment.getStudent().fullName())")
    @Mapping(target = "courseId", source = "course.id")
    @Mapping(target = "courseCode", source = "course.code")
    @Mapping(target = "courseTitle", source = "course.title")
    @Mapping(target = "courseCredits", source = "course.credits")
    @Mapping(target = "status", expression = "java(enrollment.getStatus().name())")
    @Mapping(target = "formattedGrade", expression = "java(enrollment.formattedGrade())")
    EnrollmentResponse toResponse(Enrollment enrollment);

    /**
     * MapStruct generates the loop. A one-line method that would otherwise be
     * three, and the kind of thing that adds up across forty DTOs.
     */
    List<EnrollmentResponse> toResponseList(List<Enrollment> enrollments);
}
