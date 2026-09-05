package it.unicam.cs.enrollment.spring.document;

import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.Enrollment;
import it.unicam.cs.enrollment.spring.repository.CourseRepository;
import it.unicam.cs.enrollment.spring.repository.EnrollmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * ============================================================================
 * BUILDING THE READ MODEL - PostgreSQL is the source of truth, Mongo is a view
 * ============================================================================
 * This is the whole architecture in one class, and the direction of the arrow is
 * the important part: rows are read from PostgreSQL and written as documents to
 * MongoDB. Never the other way round.
 *
 * <p>WHY THAT DIRECTION. PostgreSQL holds the constraints that make the data
 * correct - the unique index on (student, course), the foreign keys, the
 * transaction that counts seats under a lock. Those are the reasons the
 * enrollment path cannot move to a document store. Mongo holds a shape that is
 * cheap to read and impossible to corrupt, because nothing ever writes to it
 * except this projection.
 *
 * <p>That pattern has a name - CQRS, command/query responsibility segregation -
 * and it is worth knowing that the name covers a spectrum. The heavyweight
 * version has separate models, separate stores and an event log. This is the
 * light end: one writer, one derived read model, rebuilt in full. Saying "we use
 * a read model" is accurate and modest; saying "we do CQRS with event sourcing"
 * when you have this is not.
 *
 * <p>THE PRICE, and it is the same price as every cache and every materialised
 * view in this repository: the read model is STALE between rebuilds, and it can
 * be WRONG if a rebuild fails silently. Hence {@code computedAt} on every
 * document. A view without a timestamp is a view nobody can trust.
 *
 * <p>WHY REBUILD IN FULL RATHER THAN INCREMENTALLY. Full rebuilds are idempotent
 * and self-healing: whatever went wrong last time is corrected this time, and
 * there is no accumulated drift. Incremental updates are faster and require you
 * to get every event right forever. At this size, full wins. At a size where it
 * does not, the answer is change-data-capture (Debezium reading the PostgreSQL
 * write-ahead log), and the fact that the simple version stops working is the
 * reason that tool exists.
 */
@Service
// Only when the document store is switched on. Without this the bean would be
// created under every profile and fail to wire, because its repository does not
// exist when the Mongo auto-configuration is excluded.
//
// @Profile is the cleanest way to express "this part of the application is
// optional" - the beans simply are not there, rather than being there and
// throwing. Compare DemoDataSeeder, which uses it for the opposite reason.
@Profile("mongo")
public class CourseProjectionService {

    private static final Logger log = LoggerFactory.getLogger(CourseProjectionService.class);

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CourseDocumentRepository documentRepository;
    private final Clock clock;

    public CourseProjectionService(CourseRepository courseRepository,
                                   EnrollmentRepository enrollmentRepository,
                                   CourseDocumentRepository documentRepository,
                                   Clock clock) {
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.documentRepository = documentRepository;
        this.clock = clock;
    }

    /**
     * Rebuild every course document from PostgreSQL.
     *
     * <p>{@code @Transactional(readOnly = true)} covers the READS. It does NOT
     * cover the Mongo writes, and that is not an oversight - it cannot. A JPA
     * transaction and a MongoDB write are two different resource managers, and
     * there is no transaction spanning them.
     *
     * <p>THAT IS THE DUAL WRITE PROBLEM, and it is the same one
     * EnrollmentEventPublisher raises about the notification: if this method
     * fails halfway, PostgreSQL is untouched (it was read-only) and Mongo holds a
     * partial rebuild. The read model is now inconsistent, and nothing rolled it
     * back.
     *
     * <p>Survivable here because the next run repairs it and every document
     * carries a {@code computedAt} that shows how old it is. The heavyweight
     * answers - two-phase commit across PostgreSQL and Mongo - are exactly what
     * chapter 33 says is "the answer that sounds right and is almost never used".
     * A rebuild that is idempotent and re-runs on a schedule is the practical one.
     */
    @Transactional(readOnly = true)
    public int rebuildAll() {
        Instant computedAt = clock.instant();

        // deleteAll then insert, for the same reasons as the statistics job:
        // atomic from a reader point of view is not available here, but removing
        // documents for courses that no longer exist is, and an upsert-only
        // strategy would leave them behind forever.
        documentRepository.deleteAll();

        List<Course> courses = courseRepository.findAll();
        int written = 0;

        for (Course course : courses) {
            documentRepository.save(toDocument(course, computedAt));
            written++;
        }

        log.info("Course read model rebuilt: {} document(s) as of {}", written, computedAt);
        return written;
    }

    private CourseDocument toDocument(Course course, Instant computedAt) {
        CourseDocument doc = new CourseDocument();
        doc.setId(String.valueOf(course.getId()));
        doc.setCode(course.getCode());
        doc.setTitle(course.getTitle());
        doc.setCredits(course.getCredits());
        doc.setCapacity(course.getCapacity());
        doc.setAcademicYear(course.getAcademicYear());
        doc.setSemester(course.getSemester().name());

        // EMBEDDING the professor. One query per course here, which would be an
        // N+1 in a request path and is acceptable in a batch that runs on a
        // schedule. Worth being explicit that it IS an N+1 rather than pretending
        // otherwise: the fix, if this grew, is the same JOIN FETCH the read path
        // already uses.
        doc.setProfessor(new CourseDocument.ProfessorInfo(
                course.getProfessor().getStaffNumber(),
                course.getProfessor().fullName(),
                course.getProfessor().getTitle().name(),
                course.getProfessor().getDepartment()));

        // EMBEDDING the roster. This is the denormalisation that makes the read
        // cheap and the write expensive - the exact inverse of the relational
        // trade, and the whole reason both stores exist here.
        List<Enrollment> enrollments = enrollmentRepository.findByCourseAndStatus(
                course.getId(), it.unicam.cs.enrollment.spring.domain.EnrollmentStatus.ACTIVE);

        doc.setStudents(enrollments.stream()
                .map(e -> new CourseDocument.EnrolledStudent(
                        e.getStudent().getStudentNumber(),
                        e.getStudent().fullName(),
                        e.getStatus().name(),
                        e.getGrade()))
                .toList());

        long occupied = doc.getStudents().size();
        doc.setOccupiedSeats(occupied);
        doc.setAvailableSeats(Math.max(0, course.getCapacity() - occupied));
        doc.setFillRate(course.getCapacity() == 0
                ? 0.0
                : BigDecimal.valueOf(occupied * 100.0 / course.getCapacity())
                        .setScale(2, RoundingMode.HALF_UP)
                        .doubleValue());

        doc.setComputedAt(computedAt);
        return doc;
    }
}
