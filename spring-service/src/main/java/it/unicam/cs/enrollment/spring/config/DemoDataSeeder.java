package it.unicam.cs.enrollment.spring.config;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Seeds a handful of rows so the demo profile has something to show.
 *
 * <p>{@code @Profile("demo")} is the important line. The bean is not created at
 * all under any other profile, so there is no chance of this running against
 * PostgreSQL and writing sample data into a real schema. It is the Spring
 * equivalent of a CDI qualifier gating a producer, and it is the mechanism for
 * anything that must exist in one environment and not another.
 *
 * <p>{@code CommandLineRunner} runs once after the context is ready and before
 * the application is considered started. The alternatives worth knowing:
 * {@code ApplicationRunner} (the same thing with parsed arguments),
 * {@code @EventListener(ApplicationReadyEvent.class)} (runs after the port is
 * open, so it does not delay readiness), and {@code @PostConstruct} on a bean -
 * which is the WRONG one here, because it runs while the context is still being
 * built and the transaction infrastructure may not be ready.
 *
 * <p>The Jakarta EE application does the same job in
 * {@code config/DataSeeder.java} with {@code @Observes @Initialized}.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final ProfessorRepository professors;
    private final CourseRepository courses;
    private final StudentRepository students;
    private final EnrollmentRepository enrollments;
    private final Clock clock;

    public DemoDataSeeder(ProfessorRepository professors,
                          CourseRepository courses,
                          StudentRepository students,
                          EnrollmentRepository enrollments,
                          Clock clock) {
        this.professors = professors;
        this.courses = courses;
        this.students = students;
        this.enrollments = enrollments;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (courses.count() > 0) {
            return;
        }

        Professor bianchi = professors.save(new Professor("P0001", "Marco", "Bianchi",
                Email.of("marco.bianchi@unicam.it"),
                AcademicTitle.ASSOCIATE_PROFESSOR, "Computer Science"));
        Professor verdi = professors.save(new Professor("P0002", "Anna", "Verdi",
                Email.of("anna.verdi@unicam.it"),
                AcademicTitle.FULL_PROFESSOR, "Mathematics"));

        Instant opens = Instant.parse("2020-01-01T00:00:00Z");
        Instant closes = Instant.parse("2035-01-01T00:00:00Z");

        Course intro = courses.save(new Course("CS100", "Introduction to Programming",
                9, 120, Semester.FALL, 2026, bianchi, opens, closes));
        Course algorithms = courses.save(new Course("CS201", "Algorithms and Data Structures",
                12, 60, Semester.FALL, 2026, bianchi, opens, closes));
        Course databases = courses.save(new Course("CS210", "Database Systems",
                9, 3, Semester.SPRING, 2026, bianchi, opens, closes));
        Course analysis = courses.save(new Course("MA100", "Mathematical Analysis I",
                12, 200, Semester.FALL, 2026, verdi, opens, closes));

        // A CLOSED window, so the client has an enrollmentOpen=false case to
        // render. Real data always contains the case your interface forgot, and
        // seed data that only covers the happy path hides it until production.
        courses.save(new Course("CS999", "Seminar (enrollment closed)",
                3, 20, Semester.SPRING, 2026, bianchi,
                Instant.parse("2021-01-01T00:00:00Z"),
                Instant.parse("2021-02-01T00:00:00Z")));

        // A prerequisite, so the detail endpoint has one to show.
        algorithms.addPrerequisite(intro);
        courses.save(algorithms);

        List<Student> cohort = List.of(
                students.save(student("S1234567", "Giulia", "Rossi")),
                students.save(student("S1234568", "Luca", "Ferrari")),
                students.save(student("S1234569", "Sofia", "Esposito")));

        // CS210 has capacity 3 and gets two enrollments here, leaving ONE seat.
        // So a demo enrollment succeeds and the next one returns 409 COURSE_FULL
        // - which is the path worth demonstrating, and the one a happy-path
        // fixture never reaches.
        enrollments.save(Enrollment.create(cohort.get(0), databases, clock.instant()));
        enrollments.save(Enrollment.create(cohort.get(1), databases, clock.instant()));
        enrollments.save(Enrollment.create(cohort.get(0), intro, clock.instant()));
        enrollments.save(Enrollment.create(cohort.get(1), analysis, clock.instant()));

        log.info("Demo data seeded: {} courses, {} students. "
                        + "CS210 (id {}) has ONE seat left - enroll student {} to take it, "
                        + "then try the same request again for a 409.",
                courses.count(), students.count(),
                databases.getId(), cohort.get(2).getId());
    }

    private Student student(String number, String first, String last) {
        return new Student(number, first, last,
                Email.of(number.toLowerCase() + "@studenti.unicam.it"),
                LocalDate.of(2004, 5, 20), 2025);
    }
}
