/**
 * <h2>Deliberately unfinished. This is the exercise package.</h2>
 *
 * <p><b>If you are skimming this repository and have just found a pile of
 * {@code UnsupportedOperationException} and {@code // TODO}, read this first:
 * none of it is abandoned work.</b> Every stub in this package is a question
 * with a matching set of tests that specify the answer, in the same way an
 * exam paper is not a document somebody failed to finish writing.
 *
 * <p>Nothing in the application depends on these classes. They are reached only
 * from the exercise tests and the fieldbook chapters that set them. The rest of
 * {@code src/main/java} is finished, commented and exercised by the normal
 * suite - which is why {@code mvn verify} is green while these throw.
 *
 * <h3>How they are wired</h3>
 *
 * <p>The tests that specify these stubs carry the JUnit tag {@code exercise},
 * and the POM excludes that tag from the default build:
 *
 * <pre>{@code
 * <excluded.test.groups>exercise</excluded.test.groups>
 * }</pre>
 *
 * <p>So {@code mvn verify} passes with the stubs still throwing, and
 *
 * <pre>{@code
 * mvn test -Pexercises
 * }</pre>
 *
 * <p>runs only the exercise tests - 88 of them, all failing until the
 * corresponding stub is implemented. That is the intended loop: run the
 * profile, read the first failure, make it green, repeat.
 *
 * <h3>What each one is for</h3>
 *
 * <ul>
 *   <li>{@code Ex1StudentQueries} - writing JPQL by hand.</li>
 *   <li>{@code Ex2CourseWindow} - a domain rule with exacting boundaries.</li>
 *   <li>{@code Ex3TransferService} - an atomic use case across two aggregates,
 *       and the transaction that makes it atomic.</li>
 *   <li>{@code Ex4TransferController} - exposing that use case over HTTP with
 *       the right status codes.</li>
 *   <li>{@code Ex5InterviewKatas} - the ten small problems a junior Java
 *       interview actually asks, with no framework involved.</li>
 *   <li>{@code Ex6EnrollmentReport} - an aggregate report:
 *       {@code JOIN} / {@code GROUP BY} / {@code HAVING}.</li>
 * </ul>
 *
 * <p>An answer key is in {@code docs/EXERCISES.md}. Reading it before trying
 * the exercise is a way of feeling productive without becoming able to do the
 * thing, which is the failure mode this whole repository is arranged against.
 *
 * @see <a href="../../../../../../../docs/EXERCISES.md">docs/EXERCISES.md</a>
 */
package it.unicam.cs.enrollment.exercises;
