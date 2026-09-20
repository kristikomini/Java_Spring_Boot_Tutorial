package it.unicam.cs.enrollment.common;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * The academic year a request means when it does not say.
 *
 * <p><b>Why this class exists.</b> Six endpoints used to declare their default
 * year as a literal:
 *
 * <pre>{@code
 * @RequestParam(name = "year", defaultValue = "2025") int academicYear
 * }</pre>
 *
 * <p>That is correct for exactly one year. {@code defaultValue} is an
 * annotation attribute, so it must be a compile-time constant - there is no
 * expression you can put there that means "now". The literal was right when it
 * was written and then quietly stopped being right, and because the value is
 * still a valid year nothing failed: the query ran, matched nothing, and
 * {@code GET /api/courses} answered {@code 200 OK} with an empty page. A wrong
 * answer with a successful status code is the expensive kind of bug, because
 * monitoring sees a healthy endpoint.
 *
 * <p><b>The fix</b> is to stop encoding the answer in the annotation. The
 * parameter becomes {@code Integer} rather than {@code int} so that "absent"
 * has its own representation - {@code null} - distinct from any year a client
 * could send. The controller then resolves the default at request time, when
 * it can actually ask what year it is.
 *
 * <p><b>Why a {@link Clock} rather than {@code Year.now()}.</b> {@code now()}
 * reads the system clock directly, which makes the behaviour untestable: a test
 * for "defaults to the current year" would have to assert against whatever year
 * the machine running it happens to be in, and a test for the December/January
 * boundary could not be written at all. An injected {@code Clock} is a seam -
 * pass {@link Clock#fixed} and time is whatever the test needs. The same
 * argument, and the same bean, as {@code ClockConfig} and
 * {@code EnrollmentMaintenanceJob}; fieldbook chapter 20 makes the general case.
 *
 * <p><b>The calendar-year simplification.</b> An Italian {@code anno
 * accademico} starts in autumn: 2026/27 runs from roughly September 2026 to
 * August 2027, so in January 2027 the current academic year is still 2026. This
 * class deliberately does <em>not</em> model that. It returns the calendar year,
 * matching {@code EnrollmentMaintenanceJob}, which has always derived its cutoff
 * the same way. Two different definitions of "current academic year" in one
 * codebase would be worse than one imperfect definition, and the real rule is a
 * university-calendar decision - a rollover date that belongs in configuration
 * next to the enrollment windows, not hard-coded in a utility class. If that
 * requirement ever arrives, this is the one place it lands.
 */
public final class AcademicYear {

    /** No instances: this is a namespace for one function, not a thing. */
    private AcademicYear() {
    }

    /**
     * The academic year to assume for a request that omitted one.
     *
     * <p>UTC, not the server's default zone, for the same reason every other
     * instant in this codebase is UTC: the answer should not depend on which
     * machine the application happens to be deployed on.
     *
     * @param clock the injected application clock, never {@code null}
     * @return the current calendar year, e.g. {@code 2026}
     */
    public static int current(Clock clock) {
        return clock.instant().atZone(ZoneOffset.UTC).getYear();
    }

    /**
     * The year a request asked for, or the current one if it did not ask.
     *
     * <p>The whole point of the {@code Integer} parameter: {@code null} means
     * "the client said nothing", which is a different question from "the client
     * said 2025".
     *
     * @param requested the {@code year} query parameter, or {@code null}
     * @param clock     the injected application clock
     * @return {@code requested} when present, otherwise {@link #current}
     */
    public static int orCurrent(Integer requested, Clock clock) {
        return requested != null ? requested : current(clock);
    }
}
