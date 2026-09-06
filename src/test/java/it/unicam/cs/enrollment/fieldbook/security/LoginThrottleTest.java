package it.unicam.cs.enrollment.fieldbook.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every test here passes an explicit {@link Instant}. That is the whole reason
 * the class takes one instead of calling {@code Instant.now()}: a window that
 * expires after fifteen minutes is otherwise untestable without a fifteen
 * minute test.
 */
@DisplayName("LoginThrottle")
class LoginThrottleTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        throttle = new LoginThrottle();
    }

    @Test
    @DisplayName("allows attempts until the per-account limit is reached")
    void blocksAfterTooManyFailuresOnOneAccount() {
        for (int i = 0; i < LoginThrottle.MAX_PER_ACCOUNT; i++) {
            assertThat(throttle.allow("mario@unicam.it", "10.0.0.1", T0)).isTrue();
            throttle.recordFailure("mario@unicam.it", "10.0.0.1", T0);
        }
        assertThat(throttle.allow("mario@unicam.it", "10.0.0.1", T0)).isFalse();
    }

    @Test
    @DisplayName("blocks a source that spreads its attempts across many accounts")
    void blocksCredentialStuffing() {
        // The per-account counter never trips here: each address is tried once.
        // Without the second counter this attack would be unlimited, which is
        // why the class has two.
        for (int i = 0; i < LoginThrottle.MAX_PER_SOURCE; i++) {
            throttle.recordFailure("victim" + i + "@example.com", "203.0.113.7", T0);
        }
        assertThat(throttle.allow("someone-new@example.com", "203.0.113.7", T0)).isFalse();
        assertThat(throttle.allow("someone-new@example.com", "198.51.100.4", T0)).isTrue();
    }

    @Test
    @DisplayName("allows registrations until the per-source limit is reached")
    void blocksRegistrationFlood() {
        for (int i = 0; i < LoginThrottle.MAX_REGISTRATIONS_PER_SOURCE; i++) {
            assertThat(throttle.allowRegistration("203.0.113.7", T0)).isTrue();
            throttle.recordRegistrationAttempt("203.0.113.7", T0);
        }
        assertThat(throttle.allowRegistration("203.0.113.7", T0)).isFalse();
        assertThat(throttle.allowRegistration("198.51.100.4", T0)).isTrue();
    }

    @Test
    @DisplayName("counts registration attempts separately from failed logins")
    void registrationAndLoginHaveSeparateBudgets() {
        // Spending the whole login budget must not close registration. They are
        // different attacks with different limits, and a shared counter would
        // let either one exhaust the other.
        for (int i = 0; i < LoginThrottle.MAX_PER_SOURCE; i++) {
            throttle.recordFailure("victim" + i + "@example.com", "203.0.113.7", T0);
        }
        assertThat(throttle.allow("someone@example.com", "203.0.113.7", T0)).isFalse();
        assertThat(throttle.allowRegistration("203.0.113.7", T0)).isTrue();
    }

    @Test
    @DisplayName("counts a successful registration, not only a rejected one")
    void registrationCountsEveryAttempt() {
        // The distinguishing rule of this counter. recordSuccess clears the
        // account counter after a good login; there is deliberately no
        // equivalent here, because on registration the success IS the thing
        // being rationed.
        for (int i = 0; i < LoginThrottle.MAX_REGISTRATIONS_PER_SOURCE; i++) {
            throttle.recordRegistrationAttempt("203.0.113.7", T0);
        }
        throttle.recordSuccess("whoever@example.com");
        assertThat(throttle.allowRegistration("203.0.113.7", T0)).isFalse();
    }

    @Test
    @DisplayName("forgets registrations once the window has passed")
    void registrationWindowExpires() {
        for (int i = 0; i < LoginThrottle.MAX_REGISTRATIONS_PER_SOURCE; i++) {
            throttle.recordRegistrationAttempt("203.0.113.7", T0);
        }
        assertThat(throttle.allowRegistration("203.0.113.7", T0)).isFalse();

        Instant later = T0.plus(LoginThrottle.WINDOW).plusSeconds(60);
        assertThat(throttle.allowRegistration("203.0.113.7", later)).isTrue();
    }

    @Test
    @DisplayName("reports a shrinking registration wait, never zero")
    void registrationRetryAfterShrinks() {
        throttle.recordRegistrationAttempt("203.0.113.7", T0);

        long early = throttle.registrationRetryAfterSeconds("203.0.113.7", T0.plusSeconds(30));
        long late  = throttle.registrationRetryAfterSeconds("203.0.113.7", T0.plusSeconds(600));
        assertThat(early).isEqualTo(LoginThrottle.WINDOW.getSeconds() - 30);
        assertThat(late).isLessThan(early);

        // Past the window there is nothing left to wait for, and the contract
        // is still "at least one second": a Retry-After of 0 invites an
        // immediate retry, which is the one thing a 429 asks you not to do.
        assertThat(throttle.registrationRetryAfterSeconds("203.0.113.7", T0.plusSeconds(99999)))
                .isEqualTo(1);
    }


    @Test
    @DisplayName("forgets failures once the window has passed")
    void windowExpires() {
        for (int i = 0; i < LoginThrottle.MAX_PER_ACCOUNT; i++) {
            throttle.recordFailure("mario@unicam.it", "10.0.0.1", T0);
        }
        assertThat(throttle.allow("mario@unicam.it", "10.0.0.1", T0)).isFalse();

        Instant later = T0.plus(LoginThrottle.WINDOW).plusSeconds(60);
        assertThat(throttle.allow("mario@unicam.it", "10.0.0.1", later)).isTrue();
    }

    @Test
    @DisplayName("clears the account counter on success but not the source counter")
    void successClearsOnlyTheAccount() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("mario@unicam.it", "203.0.113.7", T0);
        }
        for (int i = 0; i < LoginThrottle.MAX_PER_SOURCE - 5; i++) {
            throttle.recordFailure("other" + i + "@example.com", "203.0.113.7", T0);
        }
        throttle.recordSuccess("mario@unicam.it");

        // Guessing one password out of thirty attempts must not hand the
        // attacker a clean slate for the other twenty-nine.
        assertThat(throttle.allow("mario@unicam.it", "203.0.113.7", T0)).isFalse();
    }

    @Test
    @DisplayName("is case and whitespace insensitive about the address")
    void addressIsNormalised() {
        for (int i = 0; i < LoginThrottle.MAX_PER_ACCOUNT; i++) {
            throttle.recordFailure("Mario@Unicam.IT", "10.0.0.1", T0);
        }
        // Otherwise the limit is bypassed by changing the capitalisation.
        assertThat(throttle.allow("  mario@unicam.it ", "10.0.0.1", T0)).isFalse();
    }

    @Test
    @DisplayName("reports a positive retry delay while blocked")
    void retryAfterIsUseful() {
        throttle.recordFailure("mario@unicam.it", "10.0.0.1", T0);
        assertThat(throttle.retryAfterSeconds("mario@unicam.it", "10.0.0.1", T0.plusSeconds(30)))
                .isGreaterThan(0);
    }
}
