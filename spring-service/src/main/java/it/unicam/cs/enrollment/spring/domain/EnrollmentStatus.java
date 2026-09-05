package it.unicam.cs.enrollment.spring.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The state machine, unchanged from the Jakarta EE application - because it is
 * pure Java and has nothing to do with either framework.
 *
 * <p>That is worth pausing on. Everything else in this module differs from its
 * counterpart across the repository: the annotations, the container, the way a
 * transaction is opened, the way an error becomes a status code. This file is
 * byte-for-byte the same idea, because a business rule expressed in the language
 * does not care who calls it. When people say "keep the framework out of the
 * domain", this is what they are describing, and the test for whether you
 * managed it is exactly this: could the class move to the other application
 * without an edit?
 *
 * <p>The transition table is the alternative to a {@code switch} in a service:
 * illegal moves are data, declared once, next to the states they constrain.
 */
public enum EnrollmentStatus {

    ACTIVE,
    COMPLETED,
    WITHDRAWN,
    FAILED;

    private static final Map<EnrollmentStatus, Set<EnrollmentStatus>> ALLOWED_TRANSITIONS;

    static {
        EnumMap<EnrollmentStatus, Set<EnrollmentStatus>> map = new EnumMap<>(EnrollmentStatus.class);
        map.put(ACTIVE, Collections.unmodifiableSet(EnumSet.of(COMPLETED, WITHDRAWN, FAILED)));
        map.put(FAILED, Collections.unmodifiableSet(EnumSet.of(ACTIVE, WITHDRAWN)));
        map.put(COMPLETED, Collections.unmodifiableSet(EnumSet.noneOf(EnrollmentStatus.class)));
        map.put(WITHDRAWN, Collections.unmodifiableSet(EnumSet.noneOf(EnrollmentStatus.class)));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    public boolean canTransitionTo(EnrollmentStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /**
     * A withdrawn or completed enrollment gives its seat back; an active or
     * failed one does not. This single method is what makes "how full is this
     * course" answerable, and it is why the seat count is a query rather than a
     * column - see EnrollmentRepository.countOccupiedSeats.
     */
    public boolean occupiesSeat() {
        return this == ACTIVE || this == FAILED;
    }
}
