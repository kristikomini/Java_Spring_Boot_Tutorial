package it.unicam.cs.enrollment.mail.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The backoff arithmetic, tested without waiting for a single real second.
 *
 * <p>That is the whole reason {@link RetryPolicy} takes {@code now} as a
 * parameter instead of calling {@code Instant.now()} itself. A policy that read
 * the clock internally could only be tested by sleeping - which turns a
 * millisecond test suite into a minutes-long one, and makes it flaky on a busy
 * CI machine into the bargain. Pushing the clock to the edges of the system is
 * the single highest-value testability habit there is.
 */
@DisplayName("RetryPolicy")
class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @Nested
    @DisplayName("backoff")
    class Backoff {

        @Test
        @DisplayName("doubles the wait after each failed attempt")
        void doublesEachTime() {
            RetryPolicy policy = new RetryPolicy(10, Duration.ofSeconds(30), Duration.ofHours(1));

            assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(30));
            assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofMinutes(1));
            assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofMinutes(2));
            assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofMinutes(4));
        }

        @Test
        @DisplayName("never waits longer than the cap")
        void respectsTheCap() {
            RetryPolicy policy = new RetryPolicy(20, Duration.ofSeconds(30), Duration.ofMinutes(30));

            // Without a cap, attempt 12 would be about seventeen hours.
            assertThat(policy.delayAfter(12)).isEqualTo(Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("stays capped even at an absurd attempt count")
        void clampsTheExponent() {
            RetryPolicy policy = new RetryPolicy(100, Duration.ofSeconds(30), Duration.ofMinutes(30));

            // The exponent is clamped at 30 because `1L << 64` silently wraps
            // to 1 in Java rather than overflowing. Without the clamp, a
            // corrupted attempt counter would turn a half-hour backoff into an
            // immediate hot loop - the exact failure the policy prevents.
            assertThat(policy.delayAfter(64)).isEqualTo(Duration.ofMinutes(30));
            assertThat(policy.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("treats a nonsensical attempt number as the first one")
        void handlesZeroAndNegative() {
            RetryPolicy policy = RetryPolicy.defaults();

            assertThat(policy.delayAfter(0)).isEqualTo(Duration.ofSeconds(30));
            assertThat(policy.delayAfter(-5)).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("computes the next attempt from the supplied clock")
        void nextAttemptIsRelativeToNow() {
            RetryPolicy policy = RetryPolicy.defaults();

            assertThat(policy.nextAttemptAt(2, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("budget")
    class Budget {

        @Test
        @DisplayName("is spent once attempts reach the maximum")
        void exhaustion() {
            RetryPolicy policy = RetryPolicy.withMaxAttempts(3);

            assertThat(policy.isExhausted(2)).isFalse();
            assertThat(policy.isExhausted(3)).isTrue();
            assertThat(policy.isExhausted(4)).isTrue();
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        /**
         * A value object validates in its constructor, so an invalid one cannot
         * exist. The alternative - checking in {@code delayAfter} - would
         * discover the problem hours later, on a server, inside a retry loop.
         */
        @Test
        @DisplayName("rejects a policy that could never retry or could never wait")
        void rejectsNonsense() {
            assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxAttempts");

            assertThatThrownBy(() -> new RetryPolicy(3, Duration.ZERO, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("baseDelay");

            assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMinutes(5), Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxDelay");
        }
    }
}
