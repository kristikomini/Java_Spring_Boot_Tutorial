package it.unicam.cs.enrollment.domain.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The lifecycle of an {@link Enrollment}, modelled as a STATE MACHINE.
 *
 * <h2>Why a state machine instead of scattered booleans?</h2>
 * The naive design is a handful of flags: {@code isActive}, {@code isPassed},
 * {@code hasWithdrawn}. Flags allow nonsense combinations (active AND withdrawn)
 * and every service ends up re-deriving "what does this really mean?".
 *
 * <p>A single enum with explicit legal transitions makes illegal states
 * impossible and puts the rule in ONE place. Notice that the transition table
 * lives in the enum itself rather than in the service - this is the "tell, don't
 * ask" principle: the object that owns the data owns the rules about it.
 *
 * <pre>
 *                  withdraw()
 *        ACTIVE ---------------&gt; WITHDRAWN   (terminal)
 *           |
 *           | recordGrade(&gt;= 18)
 *           v
 *        COMPLETED  (terminal)
 *           ^
 *           |  recordGrade(&lt; 18) / examFailed()
 *        FAILED --------------------&gt; ACTIVE   (student may retake)
 * </pre>
 */
public enum EnrollmentStatus {

    /** The student holds a seat and may sit the exam. */
    ACTIVE,

    /** The exam was passed and a grade recorded. Credits are awarded. */
    COMPLETED,

    /** The student left the course voluntarily. The seat is released. */
    WITHDRAWN,

    /** The exam was failed. The student keeps the seat and may retake it. */
    FAILED;

    /**
     * A static, immutable transition table.
     *
     * <p>{@link EnumSet} is the right {@code Set} for enums: internally it is a
     * bit vector, so membership tests are a single AND instruction. Reach for it
     * any time the elements are enum constants.
     *
     * <p>It is initialised in a static block because an enum constant cannot
     * reference other constants in its own constructor - they do not exist yet.
     */
    private static final java.util.Map<EnrollmentStatus, Set<EnrollmentStatus>> ALLOWED_TRANSITIONS;

    static {
        java.util.EnumMap<EnrollmentStatus, Set<EnrollmentStatus>> map =
                new java.util.EnumMap<>(EnrollmentStatus.class);
        map.put(ACTIVE, Collections.unmodifiableSet(EnumSet.of(COMPLETED, WITHDRAWN, FAILED)));
        map.put(FAILED, Collections.unmodifiableSet(EnumSet.of(ACTIVE, WITHDRAWN)));
        map.put(COMPLETED, Collections.unmodifiableSet(EnumSet.noneOf(EnrollmentStatus.class)));
        map.put(WITHDRAWN, Collections.unmodifiableSet(EnumSet.noneOf(EnrollmentStatus.class)));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    /** @return {@code true} if moving from this status to {@code target} is legal. */
    public boolean canTransitionTo(EnrollmentStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** A status from which no further transition is possible. */
    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /** An ACTIVE or FAILED enrollment still occupies a seat in the course. */
    public boolean occupiesSeat() {
        return this == ACTIVE || this == FAILED;
    }

    /**
     * The statuses that hold a seat, DERIVED from {@link #occupiesSeat()}
     * rather than listed again.
     *
     * <p>Worth the four lines. The alternative - a hand-written
     * {@code List.of(ACTIVE, FAILED)} next to the query that uses it - is a
     * second copy of the rule, and the failure mode when a status is added is
     * silent: the seat count is simply wrong, and nothing points at the list
     * that was not updated. Computing it from the predicate means there is one
     * definition and the compiler visits it.
     */
    public static List<EnrollmentStatus> occupyingSeats() {
        return Arrays.stream(values()).filter(EnrollmentStatus::occupiesSeat).toList();
    }
}
