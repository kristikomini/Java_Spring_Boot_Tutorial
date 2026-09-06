package it.unicam.cs.enrollment.fieldbook.service;

import it.unicam.cs.enrollment.domain.model.Email;
import it.unicam.cs.enrollment.exception.DuplicateResourceException;
import it.unicam.cs.enrollment.exception.InvalidRequestException;
import it.unicam.cs.enrollment.fieldbook.domain.AuthSession;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.fieldbook.domain.Username;
import it.unicam.cs.enrollment.fieldbook.repository.AuthSessionRepository;
import it.unicam.cs.enrollment.fieldbook.repository.LearnerAccountRepository;
import it.unicam.cs.enrollment.fieldbook.repository.PasswordResetTokenRepository;
import it.unicam.cs.enrollment.fieldbook.repository.ProgressRepository;
import it.unicam.cs.enrollment.fieldbook.repository.StickyNoteRepository;
import it.unicam.cs.enrollment.fieldbook.security.LoginThrottle;
import it.unicam.cs.enrollment.fieldbook.security.PasswordHasher;
import it.unicam.cs.enrollment.fieldbook.security.TokenMint;
import it.unicam.cs.enrollment.mail.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registration and sign-in, composed rather than in pieces.
 *
 * <p>The individual mechanisms have their own tests - {@code PasswordHasherTest},
 * {@code LoginThrottleTest}, {@code UsernameTest}. What those cannot catch is
 * the class of bug this file is for: every part correct, assembled in an order
 * that leaks or lets something through. So the hasher, the mint and the throttle
 * here are the REAL ones, and only the repositories are mocked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccountService (registration and sign-in)")
class AccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final String IP = "203.0.113.7";

    @Mock private LearnerAccountRepository accountRepository;
    @Mock private AuthSessionRepository sessionRepository;
    @Mock private PasswordResetTokenRepository resetRepository;
    @Mock private ProgressRepository progressRepository;
    @Mock private StickyNoteRepository noteRepository;
    @Mock private MailService mail;
    @Mock private Logger log;

    private LoginThrottle throttle;
    private AccountService service;

    /** A stand-in for the table: what has been saved, keyed by username. */
    private final Map<String, LearnerAccount> saved = new HashMap<>();

    @BeforeEach
    void setUp() {
        throttle = new LoginThrottle();
        service = new AccountService(accountRepository, sessionRepository, resetRepository,
                progressRepository, noteRepository, mail,
                new PasswordHasher(), new TokenMint(), throttle,
                Clock.fixed(NOW, ZoneOffset.UTC), log);

        saved.clear();
        doAnswer(call -> {
            LearnerAccount a = call.getArgument(0);
            saved.put(a.getUsername().getValue(), a);
            return a;
        }).when(accountRepository).save(any(LearnerAccount.class));

        when(accountRepository.existsByUsername(anyString()))
                .thenAnswer(c -> saved.containsKey(c.getArgument(0)));
        when(accountRepository.existsByEmail(anyString())).thenReturn(false);
        when(accountRepository.findByUsername(anyString()))
                .thenAnswer(c -> Optional.ofNullable(saved.get((String) c.getArgument(0))));
        when(sessionRepository.save(any(AuthSession.class))).thenAnswer(c -> c.getArgument(0));
    }

    private AccountService.Login register(String user, String password) {
        return service.register(user, user.replaceAll("[^a-z0-9]", "") + "@unicam.it", null,
                password.toCharArray(), "Europe/Rome", IP, "JUnit");
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("accepts a one-character password and signs the account straight in")
        void shortPasswordIsAccepted() {
            AccountService.Login login = register("mario", "x");

            assertThat(login.isOk()).isTrue();
            assertThat(login.getRawToken()).isNotBlank();
            assertThat(login.getAccount().getUsername().getValue()).isEqualTo("mario");
        }

        @Test
        @DisplayName("accepts a username the old shape rule would have refused")
        void relaxedUsernameIsAccepted() {
            assertThat(register("mario rossi", "x").isOk()).isTrue();
            assertThat(register(".mario.", "x").isOk()).isTrue();
        }

        @Test
        @DisplayName("still refuses an empty password")
        void emptyPasswordIsRefused() {
            // The one rule left, and the reason it is left: an account anybody
            // can open by leaving the box blank is not a weak account, it is a
            // public one.
            assertThatThrownBy(() -> register("mario", ""))
                    .isInstanceOf(InvalidRequestException.class);
            verify(accountRepository, never()).save(any(LearnerAccount.class));
        }

        @Test
        @DisplayName("still refuses a duplicate username")
        void duplicateUsernameIsRefused() {
            assertThat(register("mario", "x").isOk()).isTrue();
            assertThatThrownBy(() -> register("mario", "y"))
                    .isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        @DisplayName("hashes the password rather than storing it")
        void passwordIsHashed() {
            register("mario", "hunter2");

            String stored = saved.get("mario").getPasswordHash();
            assertThat(stored).doesNotContain("hunter2").startsWith("pbkdf2-sha256$210000$");
            assertThat(new PasswordHasher().matches("hunter2".toCharArray(), stored)).isTrue();
        }
    }

    @Nested
    @DisplayName("sign-in")
    class SignIn {

        @Test
        @DisplayName("accepts the short password that was just registered")
        void roundTrip() {
            register("mario", "x");

            AccountService.Login login = service.login("mario", "x".toCharArray(), IP, "JUnit");

            assertThat(login.isOk()).isTrue();
            assertThat(login.getRawToken()).isNotBlank();
        }

        @Test
        @DisplayName("accepts the handle typed back in a different case, with stray spaces")
        void normalisesTheHandle() {
            // The half of the old username rule that was actually load-bearing.
            // If this ever stops holding, people will create an account and be
            // unable to sign in to it, which is the worst bug this area has.
            register("mario", "x");

            assertThat(service.login("  MARIO ", "x".toCharArray(), IP, "JUnit").isOk()).isTrue();
        }

        @Test
        @DisplayName("refuses the wrong password")
        void wrongPassword() {
            register("mario", "x");

            AccountService.Login login = service.login("mario", "y".toCharArray(), IP, "JUnit");
            assertThat(login.getResult()).isEqualTo(AccountService.LoginResult.BAD_CREDENTIALS);
            assertThat(login.getRawToken()).isNull();
        }

        @Test
        @DisplayName("answers an unknown handle exactly as it answers a wrong password")
        void noEnumerationLeak() {
            register("mario", "x");

            AccountService.Login unknown =
                    service.login("nobody", "x".toCharArray(), IP, "JUnit");
            AccountService.Login wrong =
                    service.login("mario", "y".toCharArray(), IP, "JUnit");

            assertThat(unknown.getResult()).isEqualTo(wrong.getResult());
            assertThat(unknown.getAccount()).isNull();
            assertThat(wrong.getAccount()).isNull();
        }
    }

    @Nested
    @DisplayName("throttling")
    class Throttling {

        /**
         * The limits on {@code LoginThrottle} are package-private on purpose, so
         * this asks the service where the wall is instead of being told. That
         * also keeps these tests from failing merely because somebody tuned a
         * number: what is being asserted is that a wall exists and behaves,
         * which is the part that would be a bug to lose.
         */
        private static final int PLENTY = 200;

        private AccountService.Login registerUntilThrottled() {
            for (int i = 0; i < PLENTY; i++) {
                AccountService.Login attempt = register("learner" + i, "x");
                if (attempt.getResult() == AccountService.LoginResult.THROTTLED) {
                    return attempt;
                }
            }
            throw new AssertionError(
                    "registration was never throttled in " + PLENTY + " attempts from one address");
        }

        @Test
        @DisplayName("stops a registration flood from one address")
        void registrationIsThrottled() {
            AccountService.Login refused = registerUntilThrottled();

            assertThat(refused.getResult()).isEqualTo(AccountService.LoginResult.THROTTLED);
            assertThat(refused.getRawToken()).isNull();
            // Refused before the row was written, not after.
            assertThat(saved).hasSizeLessThan(PLENTY);
        }

        @Test
        @DisplayName("counts a rejected registration too, so failing does not buy free attempts")
        void rejectedRegistrationsAreCounted() {
            register("mario", "x");

            // Every one of these is refused by the 409 path, and every one must
            // still be charged to the source's budget. If only successes were
            // counted this loop would be free, and a duplicate-username probe
            // would be an unlimited way to read the account list.
            for (int i = 0; i < PLENTY; i++) {
                try {
                    AccountService.Login attempt = register("mario", "x");
                    assertThat(attempt.getResult())
                            .isEqualTo(AccountService.LoginResult.THROTTLED);
                    return;
                } catch (DuplicateResourceException expected) {
                    // charged, and on to the next
                }
            }
            throw new AssertionError("rejected registrations were never counted");
        }

        @Test
        @DisplayName("reports a real remaining wait, not a constant")
        void retryAfterIsReal() {
            long wait = registerUntilThrottled().getRetryAfterSeconds();

            // 900 was the old hard-coded value; anything inside the window and
            // above zero is a real measurement.
            assertThat(wait).isPositive().isLessThanOrEqualTo(900);
        }

        @Test
        @DisplayName("carries the wait on a throttled sign-in as well")
        void loginRetryAfterIsReal() {
            register("mario", "x");

            AccountService.Login refused = null;
            for (int i = 0; i < PLENTY && refused == null; i++) {
                AccountService.Login attempt =
                        service.login("mario", "wrong".toCharArray(), IP, "JUnit");
                if (attempt.getResult() == AccountService.LoginResult.THROTTLED) {
                    refused = attempt;
                }
            }

            assertThat(refused).as("sign-in was never throttled").isNotNull();
            assertThat(refused.getRetryAfterSeconds()).isPositive();
        }

        @Test
        @DisplayName("does not spend the registration budget of a different address")
        void throttleIsPerSource() {
            registerUntilThrottled();

            AccountService.Login elsewhere = service.register("somebodyelse", "e@unicam.it", null,
                    "x".toCharArray(), "Europe/Rome", "198.51.100.4", "JUnit");
            assertThat(elsewhere.isOk()).isTrue();
        }

        @Test
        @DisplayName("leaves sign-in working for everybody else while it holds")
        void throttlingRegistrationDoesNotBlockSignIn() {
            // The two counters are separate, and this is why that matters in
            // practice: a signup flood must not lock existing learners out.
            register("mario", "x");
            registerUntilThrottled();

            assertThat(service.login("mario", "x".toCharArray(), "198.51.100.4", "JUnit").isOk())
                    .isTrue();
        }
    }

    /**
     * Not a behaviour test: a reminder that the value objects are still doing
     * the normalising, since the service now leans on them for almost all of
     * what used to be its own validation.
     */
    @Test
    @DisplayName("stores the username and the address normalised")
    void valuesAreNormalised() {
        service.register("  MaRiO  ", "  MARIO@UNICAM.IT ", null,
                "x".toCharArray(), "Europe/Rome", IP, "JUnit");

        LearnerAccount account = saved.get("mario");
        assertThat(account).isNotNull();
        assertThat(account.getUsername()).isEqualTo(Username.of("mario"));
        assertThat(account.getEmail()).isEqualTo(Email.of("mario@unicam.it"));
    }
}
