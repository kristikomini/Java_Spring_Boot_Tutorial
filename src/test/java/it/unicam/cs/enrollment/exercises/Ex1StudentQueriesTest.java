package it.unicam.cs.enrollment.exercises;

import it.unicam.cs.enrollment.domain.model.Email;
import it.unicam.cs.enrollment.domain.model.Student;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specification for Exercise 1. Runs against in-memory H2 - no server needed.
 *
 * <p>This really is an integration test - it starts JPA and talks to H2, because
 * the thing under test <em>is</em> the query. Mocking the EntityManager here
 * would prove only that you can call a mock.
 *
 * <p>It is named {@code *Test} rather than {@code *IT}, breaking the convention
 * the rest of the project follows, for one practical reason: Failsafe runs after
 * Surefire, so an {@code *IT} exercise would not run at all until every other
 * exercise already passed. Keeping all four in Surefire means one command shows
 * you the whole scoreboard.
 */
@Tag("exercise")
@DisplayName("Exercise 1: findByEnrollmentYear (H2)")
class Ex1StudentQueriesTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private Ex1StudentQueries queries;

    @BeforeAll
    static void createFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("enrollmentTestPU");
    }

    @AfterAll
    static void closeFactory() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    @BeforeEach
    void setUp() {
        entityManager = entityManagerFactory.createEntityManager();
        queries = new Ex1StudentQueries(entityManager);
        entityManager.getTransaction().begin();
    }

    @AfterEach
    void tearDown() {
        if (entityManager.getTransaction().isActive()) {
            entityManager.getTransaction().rollback();
        }
        entityManager.close();
    }

    private Student aStudent(String number, String last, int enrollmentYear) {
        return new Student(number, "Test", last,
                Email.of(number + "@studenti.unicam.it"),
                LocalDate.of(2004, 3, 14), enrollmentYear);
    }

    private void given(Student... students) {
        for (Student student : students) {
            entityManager.persist(student);
        }
        entityManager.flush();
    }

    @Test
    @DisplayName("returns only the students from the requested year")
    void filtersByYear() {
        given(aStudent("200001", "Rossi", 2023),
              aStudent("200002", "Bianchi", 2024),
              aStudent("200003", "Verdi", 2024));

        List<Student> result = queries.findByEnrollmentYear(2024);

        assertThat(result)
                .extracting(Student::getStudentNumber)
                .containsExactlyInAnyOrder("200002", "200003");
    }

    @Test
    @DisplayName("orders by student number ascending")
    void ordersByStudentNumber() {
        given(aStudent("200030", "Rossi", 2024),
              aStudent("200010", "Bianchi", 2024),
              aStudent("200020", "Verdi", 2024));

        List<Student> result = queries.findByEnrollmentYear(2024);

        assertThat(result)
                .extracting(Student::getStudentNumber)
                .containsExactly("200010", "200020", "200030");
    }

    @Test
    @DisplayName("returns an empty list, not null, when nothing matches")
    void emptyWhenNoMatch() {
        given(aStudent("200001", "Rossi", 2023));

        List<Student> result = queries.findByEnrollmentYear(1999);

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("a value that looks like an injection attempt is just a number")
    void yearIsBound() {
        given(aStudent("200001", "Rossi", 2024));

        // There is no string to inject into: the parameter is an int, and it is
        // bound rather than concatenated. This test mostly exists to make the
        // point that binding is what makes that true - see fieldbook chapter 10.
        assertThat(queries.findByEnrollmentYear(2024)).hasSize(1);
        assertThat(queries.findByEnrollmentYear(-1)).isEmpty();
    }
}
