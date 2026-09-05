package it.unicam.cs.enrollment.spring.document;

import it.unicam.cs.enrollment.spring.domain.AcademicTitle;
import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.Email;
import it.unicam.cs.enrollment.spring.domain.Enrollment;
import it.unicam.cs.enrollment.spring.domain.Professor;
import it.unicam.cs.enrollment.spring.domain.Semester;
import it.unicam.cs.enrollment.spring.domain.Student;
import it.unicam.cs.enrollment.spring.repository.CourseRepository;
import it.unicam.cs.enrollment.spring.repository.EnrollmentRepository;
import it.unicam.cs.enrollment.spring.repository.ProfessorRepository;
import it.unicam.cs.enrollment.spring.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 * THE DOCUMENT STORE, ACTUALLY RUNNING
 * ============================================================================
 * A REAL mongod, started by flapdoodle as a child process. No Docker, no
 * installation - the binary is downloaded once and cached, so the first run
 * needs network and every run afterwards is local.
 *
 * <p>That choice is deliberate. The fieldbook mentions NoSQL in one paragraph,
 * and a paragraph is exactly the level at which people say things about
 * MongoDB that turn out not to be true. Every claim below is executed.
 *
 * <p>{@code @ActiveProfiles({"test", "mongo"})} composes two profiles: `test`
 * gives H2 for the relational side, `mongo` re-enables the document store that
 * application.yml excludes by default. Both stores are live in this one test,
 * which is what makes the projection testable end to end.
 *
 * <p>WHAT THIS TEST CANNOT DO, and the limitation is the lesson rather than an
 * inconvenience: flapdoodle runs a SINGLE NODE, and MongoDB supports
 * multi-document transactions only on a replica set. So there is no test here
 * for "roll back a write across two documents" - because on this deployment
 * there is no such thing. If your design needs that, the document store is
 * fighting you and the relational database was the right answer.
 */
@SpringBootTest
@ActiveProfiles({"test", "mongo"})
@DisplayName("MongoDB as a read model beside PostgreSQL")
class CourseDocumentTest {

    @Autowired
    private CourseDocumentRepository documents;

    @Autowired
    private CourseProjectionService projection;

    @Autowired
    private CourseRepository courses;

    @Autowired
    private ProfessorRepository professors;

    @Autowired
    private StudentRepository students;

    @Autowired
    private EnrollmentRepository enrollments;

    @BeforeEach
    void seed() {
        documents.deleteAll();
        enrollments.deleteAllInBatch();
        courses.deleteAllInBatch();
        students.deleteAllInBatch();
        professors.deleteAllInBatch();

        Professor bianchi = professors.saveAndFlush(new Professor("P0001", "Marco", "Bianchi",
                Email.of("marco.bianchi@unicam.it"),
                AcademicTitle.ASSOCIATE_PROFESSOR, "Computer Science"));

        Instant opens = Instant.parse("2020-01-01T00:00:00Z");
        Instant closes = Instant.parse("2035-01-01T00:00:00Z");

        Course algorithms = courses.saveAndFlush(new Course("CS201", "Algorithms",
                12, 10, Semester.FALL, 2026, bianchi, opens, closes));
        courses.saveAndFlush(new Course("CS999", "Empty Seminar",
                3, 20, Semester.SPRING, 2026, bianchi, opens, closes));

        Student giulia = students.saveAndFlush(new Student("S1234567", "Giulia", "Rossi",
                Email.of("giulia.rossi@studenti.unicam.it"), LocalDate.of(2004, 3, 12), 2025));
        Student luca = students.saveAndFlush(new Student("S1234568", "Luca", "Ferrari",
                Email.of("luca.ferrari@studenti.unicam.it"), LocalDate.of(2004, 6, 1), 2025));

        enrollments.saveAndFlush(Enrollment.create(giulia, algorithms, Instant.now()));
        enrollments.saveAndFlush(Enrollment.create(luca, algorithms, Instant.now()));
    }

    @Test
    @DisplayName("the projection writes one document per course, from PostgreSQL")
    void rebuildsFromTheRelationalSource() {
        assertThat(documents.count()).isZero();

        int written = projection.rebuildAll();

        assertThat(written).isEqualTo(2);
        assertThat(documents.count()).isEqualTo(2);

        // The id carries over from PostgreSQL, so the two stores can be
        // reconciled when somebody has to work out why the read model is wrong.
        Course algorithms = courses.findByCodeAndAcademicYear("CS201", 2026).orElseThrow();
        assertThat(documents.findById(String.valueOf(algorithms.getId()))).isPresent();
    }

    @Test
    @DisplayName("the professor is EMBEDDED - one read, no join")
    void embedsTheProfessor() {
        projection.rebuildAll();

        CourseDocument doc = documents.findByCodeAndAcademicYear("CS201", 2026).orElseThrow();

        // In PostgreSQL this is a foreign key and a join. Here the professor is
        // inside the document, so rendering the page is ONE read of ONE
        // collection. That is what document stores are genuinely good at, and it
        // is bought with the denormalisation the next test measures.
        assertThat(doc.getProfessor()).isNotNull();
        assertThat(doc.getProfessor().fullName()).isEqualTo("Marco Bianchi");
        assertThat(doc.getProfessor().department()).isEqualTo("Computer Science");
    }

    @Test
    @DisplayName("the roster is EMBEDDED as an array inside the course document")
    void embedsTheRoster() {
        projection.rebuildAll();

        CourseDocument doc = documents.findByCodeAndAcademicYear("CS201", 2026).orElseThrow();

        assertThat(doc.getStudents())
                .extracting(CourseDocument.EnrolledStudent::studentNumber)
                .containsExactlyInAnyOrder("S1234567", "S1234568");

        assertThat(doc.getOccupiedSeats()).isEqualTo(2);
        assertThat(doc.getAvailableSeats()).isEqualTo(8);

        // The empty course gets an empty array, not a missing field. Worth
        // asserting: a reader that has to distinguish null from empty has a bug
        // waiting for the first course nobody enrolled in.
        CourseDocument empty = documents.findByCodeAndAcademicYear("CS999", 2026).orElseThrow();
        assertThat(empty.getStudents()).isEmpty();
        assertThat(empty.getOccupiedSeats()).isZero();
    }

    @Test
    @DisplayName("a query INTO the embedded array replaces the relational join")
    void queriesInsideTheEmbeddedArray() {
        projection.rebuildAll();

        // { 'students.studentNumber': ?0 } - dot notation reaches inside the
        // embedded documents, and Mongo matches if ANY array element matches.
        //
        // In PostgreSQL this question needs a join to enrollments. Here it is
        // one read of one collection, because the roster was embedded for
        // exactly this access pattern.
        //
        // Which is the whole lesson: the schema is shaped by the QUERIES you
        // intend, not by the entities you have. Model the wrong access pattern
        // and no index saves you - you rewrite the documents.
        List<CourseDocument> giuliaCourses = documents.findCoursesForStudent("S1234567");

        assertThat(giuliaCourses).hasSize(1);
        assertThat(giuliaCourses.get(0).getCode()).isEqualTo("CS201");

        assertThat(documents.findCoursesForStudent("S9999999")).isEmpty();
    }

    @Test
    @DisplayName("derived queries work exactly as they do in Spring Data JPA")
    void derivedQueriesPortAcross() {
        projection.rebuildAll();

        // Same mechanism, different store, no code difference. This portability
        // is the honest answer to "how hard is MongoDB coming from JPA": the
        // repository layer is nearly free. The MODELLING is where the work is.
        assertThat(documents.findByAcademicYearOrderByCodeAsc(2026))
                .extracting(CourseDocument::getCode)
                .containsExactly("CS201", "CS999");

        assertThat(documents.findByAcademicYearOrderByCodeAsc(2020)).isEmpty();
    }

    @Test
    @DisplayName("a JSON filter with an operator, and a projection that limits the fields")
    void jsonFilterAndProjection() {
        projection.rebuildAll();

        // CS201 is 20% full, CS999 is 0%. Below 25% catches both.
        List<CourseDocument> underSubscribed = documents.findUnderSubscribed(25.0);
        assertThat(underSubscribed).hasSize(2);

        // Below 10% catches only the empty one.
        assertThat(documents.findUnderSubscribed(10.0))
                .extracting(CourseDocument::getCode)
                .containsExactly("CS999");

        // THE PROJECTION IS THE POINT. The query asked for code, title, fillRate
        // and capacity, so the embedded roster did NOT come back - null rather
        // than an empty list, because the field was never read.
        //
        // Selecting columns is a modest optimisation in SQL. In a document store
        // it is the difference between transferring a kilobyte and transferring
        // the entire embedded array to answer a question about one number.
        CourseDocument projected = documents.findUnderSubscribed(10.0).get(0);
        assertThat(projected.getCode()).isEqualTo("CS999");
        assertThat(projected.getStudents()).isNull();
        assertThat(projected.getProfessor()).isNull();
    }

    @Test
    @DisplayName("the rebuild is idempotent and self-healing")
    void rebuildIsIdempotent() {
        projection.rebuildAll();
        long first = documents.count();

        projection.rebuildAll();

        assertThat(documents.count()).isEqualTo(first).isEqualTo(2);

        // Delete a course from PostgreSQL and rebuild: the document goes too.
        // An upsert-only strategy would leave it behind forever, and nobody
        // would notice until a dashboard showed a course that no longer exists.
        Course empty = courses.findByCodeAndAcademicYear("CS999", 2026).orElseThrow();
        courses.delete(empty);
        courses.flush();

        projection.rebuildAll();

        assertThat(documents.count()).isEqualTo(1);
        assertThat(documents.findByCodeAndAcademicYear("CS999", 2026)).isEmpty();
    }

    @Test
    @DisplayName("every document carries computedAt, so staleness is visible")
    void documentsCarryTheirTimestamp() {
        projection.rebuildAll();

        // A derived view without a timestamp is a view nobody can trust: there
        // is no way to tell live data from the output of a job that has been
        // failing since Friday. Same reasoning as course_statistics.computed_at.
        assertThat(documents.findAll())
                .isNotEmpty()
                .allSatisfy(doc -> assertThat(doc.getComputedAt()).isNotNull());
    }

    @Test
    @DisplayName("the document store has no foreign keys - nothing stops a dangling reference")
    void thereAreNoForeignKeys() {
        projection.rebuildAll();
        assertThat(documents.count()).isEqualTo(2);

        // Delete the course from PostgreSQL WITHOUT rebuilding.
        Course algorithms = courses.findByCodeAndAcademicYear("CS201", 2026).orElseThrow();
        enrollments.deleteAllInBatch();
        courses.delete(algorithms);
        courses.flush();

        // The document is still there, referencing a course that no longer
        // exists. MongoDB did not notice and could not have: there are no
        // foreign keys across collections, let alone across databases.
        //
        // This is the concrete version of what chapter 33 means by saying this
        // application "would be materially harder to make correct in a document
        // store". In PostgreSQL a foreign key makes this state unrepresentable.
        // Here, keeping it correct is the job of the code that writes it - which
        // is fine for a read model that gets rebuilt, and would be alarming for a
        // system of record.
        assertThat(documents.findByCodeAndAcademicYear("CS201", 2026)).isPresent();
    }
}
