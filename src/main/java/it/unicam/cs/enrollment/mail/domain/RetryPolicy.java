package it.unicam.cs.enrollment.mail.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * How long to wait before attempting a failed message again.
 *
 * <h2>Why not just retry immediately, in a loop</h2>
 * Because the usual reason a send fails is that the far end is overloaded or
 * down, and an immediate retry loop is the single most effective way to keep it
 * that way. Three properties make a retry policy safe:
 *
 * <ul>
 *   <li><b>Backoff</b> - each attempt waits longer than the last, so a
 *       ten-minute outage costs a handful of attempts rather than thousands.
 *       Doubling ({@code base * 2^n}) is the standard shape.</li>
 *   <li><b>A cap</b> - unbounded doubling reaches "next attempt in nine days"
 *       by attempt 15. The cap keeps the tail bounded.</li>
 *   <li><b>A budget</b> - {@code maxAttempts}. Something has to decide that a
 *       message is never going to be delivered, or the queue grows forever and
 *       nobody ever looks at the failures.</li>
 * </ul>
 *
 * <h2>The fourth property, and why it is not here</h2>
 * Production policies add JITTER: a random offset, so that a thousand messages
 * that failed together do not all wake up together and re-create the stampede
 * they were meant to avoid. It is omitted deliberately - a deterministic policy
 * is one that can be unit-tested by equality, and this system's batch limit
 * already spreads the load. Know that it is missing; add it the day this queue
 * is measured in thousands rather than dozens.
 *
 * <p>The class is a VALUE: immutable, no dependencies, no clock of its own. All
 * the time-dependent logic takes {@code now} as a parameter, which is what makes
 * every case below testable without waiting for real minutes to pass.
 */
public final class RetryPolicy {

    /** Sensible for mail: quick first retry, then back off to a few minutes. */
    private static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(30);
    private static final Duration DEFAULT_MAX_DELAY = Duration.ofMinutes(30);
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;

    public RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be shorter than baseDelay");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_DELAY, DEFAULT_MAX_DELAY);
    }

    /** Same shape, different budget - what {@code MailConfig} builds. */
    public static RetryPolicy withMaxAttempts(int maxAttempts) {
        return new RetryPolicy(maxAttempts, DEFAULT_BASE_DELAY, DEFAULT_MAX_DELAY);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * The wait after {@code attempt} failures: 30s, 1m, 2m, 4m, ... capped.
     *
     * <p>The shift is done on a {@code long} count of seconds rather than by
     * multiplying the {@code Duration}, and the exponent is clamped at 30,
     * because {@code 1L << 64} silently wraps around to 1 in Java rather than
     * overflowing to something obviously wrong. An attempt counter that
     * accidentally reaches 64 would otherwise turn a 30-minute backoff into an
     * instant hot loop - the exact failure the policy exists to prevent.
     */
    public Duration delayAfter(int attempt) {
        if (attempt < 1) {
            return baseDelay;
        }
        int exponent = Math.min(attempt - 1, 30);
        long seconds = baseDelay.getSeconds() << exponent;
        Duration delay = Duration.ofSeconds(seconds);
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }

    /** When a message that has just failed for the {@code attempt}-th time is due again. */
    public Instant nextAttemptAt(int attempt, Instant now) {
        return now.plus(delayAfter(attempt));
    }

    /** True once the budget is spent and the message should be declared DEAD. */
    public boolean isExhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    @Override
    public String toString() {
        return "RetryPolicy{maxAttempts=" + maxAttempts
                + ", base=" + baseDelay
                + ", cap=" + maxDelay + '}';
    }
}
