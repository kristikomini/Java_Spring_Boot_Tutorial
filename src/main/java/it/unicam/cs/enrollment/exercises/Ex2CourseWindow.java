package it.unicam.cs.enrollment.exercises;

import it.unicam.cs.enrollment.domain.model.Course;

import java.time.Instant;

/**
 * EXERCISE 2 - A domain rule, and its boundaries (the domain layer)
 * =============================================================================
 * Difficulty: small, but the boundaries are the whole point.
 *
 * <p>Run the tests for this exercise with:
 * <pre>mvn test -Pexercises -Dtest=Ex2CourseWindowTest</pre>
 *
 * <h2>What to do</h2>
 * {@code Course.isEnrollmentOpen(now)} answers a yes/no question. That is often
 * not enough: a closed course and a course that has not opened yet deserve
 * different messages to the user. Implement {@link #windowFor(Course, Instant)}
 * to return which of the three states the course is in.
 *
 * <h2>What you are practising</h2>
 * <ul>
 *   <li><strong>Half-open intervals.</strong> The existing window is
 *       {@code [opensAt, closesAt)} - inclusive at the start, exclusive at the
 *       end. Read {@code Course.isEnrollmentOpen} and match it exactly. Half-open
 *       intervals compose without gaps or overlaps, which is why they are the
 *       convention worth defaulting to.</li>
 *   <li><strong>Boundary testing.</strong> The tests check the exact instants
 *       {@code opensAt} and {@code closesAt}, not just the middle. Off-by-one
 *       errors live precisely there and nowhere else.</li>
 *   <li><strong>Time as a parameter.</strong> {@code now} is passed in rather
 *       than read from the system clock, so the tests are deterministic. That is
 *       the same idea as the injected {@code Clock} in {@code ClockProducer}.</li>
 * </ul>
 *
 * <h2>The exact contract</h2>
 * <table border="1">
 *   <caption>Expected result by instant</caption>
 *   <tr><th>Condition</th><th>Result</th></tr>
 *   <tr><td>{@code now} strictly before {@code opensAt}</td><td>{@code NOT_YET_OPEN}</td></tr>
 *   <tr><td>{@code now} exactly {@code opensAt}</td><td>{@code OPEN}</td></tr>
 *   <tr><td>{@code now} between them</td><td>{@code OPEN}</td></tr>
 *   <tr><td>{@code now} exactly {@code closesAt}</td><td>{@code CLOSED}</td></tr>
 *   <tr><td>{@code now} after {@code closesAt}</td><td>{@code CLOSED}</td></tr>
 * </table>
 *
 * <p>A null {@code course} or {@code now} must raise
 * {@link NullPointerException} - fail loudly rather than returning a
 * meaningless answer.
 */
public final class Ex2CourseWindow {

    private Ex2CourseWindow() {
    }

    /** The three states an enrollment window can be in. */
    public enum Window {
        /** The window has not started yet. */
        NOT_YET_OPEN,
        /** Enrollment is possible right now. */
        OPEN,
        /** The window has ended. */
        CLOSED
    }

    /**
     * Which state {@code course}'s enrollment window is in at {@code now}.
     *
     * @param course the course to inspect; must not be null
     * @param now    the instant to evaluate at; must not be null
     * @return the window state
     * @throws NullPointerException if either argument is null
     */
    public static Window windowFor(Course course, Instant now) {
        // TODO Exercise 2: replace this line.
        // Read Course.isEnrollmentOpen first and keep the same boundary rules.
        throw new UnsupportedOperationException(
                "Exercise 2 not implemented yet - see the Javadoc above for the contract.");
    }
}
