package it.unicam.cs.enrollment.fieldbook.domain;

import it.unicam.cs.enrollment.domain.model.Email;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The streak rules, which are the part of a study tool people complain about
 * loudest and the part with the least logic in it.
 */
@DisplayName("LearnerAccount")
class LearnerAccountTest {

    private static final LocalDate MON = LocalDate.of(2026, 3, 2);

    private LearnerAccount account() {
        return LearnerAccount.register(
                Email.of("mario@unicam.it"), "Mario", "pbkdf2-sha256$1$a$b", "Europe/Rome");
    }

    @Test
    @DisplayName("starts with no streak and the learner role")
    void freshAccount() {
        LearnerAccount a = account();
        assertThat(a.currentStreak(MON)).isZero();
        assertThat(a.getBestStreak()).isZero();
        assertThat(a.hasRole(LearnerAccount.ROLE_LEARNER)).isTrue();
        assertThat(a.hasRole(LearnerAccount.ROLE_AUTHOR)).isFalse();
    }

    @Test
    @DisplayName("counts consecutive days")
    void consecutiveDays() {
        LearnerAccount a = account();
        a.recordStudyDay(MON);
        a.recordStudyDay(MON.plusDays(1));
        a.recordStudyDay(MON.plusDays(2));

        assertThat(a.currentStreak(MON.plusDays(2))).isEqualTo(3);
    }

    @Test
    @DisplayName("a streak whose last day was yesterday is still alive today")
    void yesterdayKeepsTheStreak() {
        LearnerAccount a = account();
        a.recordStudyDay(MON);
        a.recordStudyDay(MON.plusDays(1));

        // This is the rule every streak app gets asked about: you have not
        // studied yet today, and it is not gone until the day ends.
        assertThat(a.currentStreak(MON.plusDays(2))).isEqualTo(2);
    }

    @Test
    @DisplayName("a missed day ends the streak")
    void gapBreaksTheStreak() {
        LearnerAccount a = account();
        a.recordStudyDay(MON);
        a.recordStudyDay(MON.plusDays(1));

        assertThat(a.currentStreak(MON.plusDays(3))).isZero();
    }

    @Test
    @DisplayName("keeps the best streak after the current one is broken")
    void bestStreakIsAHighWaterMark() {
        LearnerAccount a = account();
        for (int i = 0; i < 5; i++) {
            a.recordStudyDay(MON.plusDays(i));
        }
        assertThat(a.getBestStreak()).isEqualTo(5);

        a.recordStudyDay(MON.plusDays(20));
        assertThat(a.currentStreak(MON.plusDays(20))).isEqualTo(1);
        assertThat(a.getBestStreak()).isEqualTo(5);
    }

    @Test
    @DisplayName("recording the same day twice changes nothing and says so")
    void recordingIsIdempotent() {
        LearnerAccount a = account();
        assertThat(a.recordStudyDay(MON)).isTrue();
        assertThat(a.recordStudyDay(MON)).isFalse();
        assertThat(a.getStudyDays()).hasSize(1);
    }

    @Test
    @DisplayName("refuses to be built without a display name")
    void requiresADisplayName() {
        assertThatThrownBy(() -> LearnerAccount.register(
                Email.of("x@y.it"), "   ", "hash", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("does not hand out a mutable view of its own collections")
    void collectionsAreEncapsulated() {
        LearnerAccount a = account();
        assertThatThrownBy(() -> a.getRoles().add("admin"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> a.getStudyDays().add(MON))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
