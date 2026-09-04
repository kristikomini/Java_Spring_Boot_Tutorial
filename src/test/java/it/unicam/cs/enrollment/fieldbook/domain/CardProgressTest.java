package it.unicam.cs.enrollment.fieldbook.domain;

import it.unicam.cs.enrollment.domain.model.Email;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CardProgress (the Leitner schedule)")
class CardProgressTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private CardProgress card() {
        LearnerAccount a = LearnerAccount.register(Email.of("m@u.it"), "M", "hash", null);
        return CardProgress.start(a, "quiz:abc", "ch-persistence");
    }

    @Test
    @DisplayName("a new card is due immediately and in box 1")
    void newCardIsDue() {
        CardProgress c = card();
        assertThat(c.getBox()).isEqualTo(1);
        assertThat(c.isDue(NOW)).isTrue();
        assertThat(c.isKnown()).isFalse();
        assertThat(c.strength()).isZero();
    }

    @Test
    @DisplayName("a right answer moves up one box and pushes the due date out")
    void rightAnswerPromotes() {
        CardProgress c = card();
        c.record(true, NOW);

        // Box 2 is the one-day interval. The ten minute step is box 1, which is
        // where a WRONG answer puts you - so a first correct answer buys a day,
        // not ten minutes. These intervals are the same numbers the browser
        // uses offline, on purpose: one rule, two implementations, and they
        // must not drift.
        assertThat(c.getBox()).isEqualTo(2);
        assertThat(c.isDue(NOW)).isFalse();
        assertThat(c.isDue(NOW.plus(23, ChronoUnit.HOURS))).isFalse();
        assertThat(c.isDue(NOW.plus(25, ChronoUnit.HOURS))).isTrue();
    }

    @Test
    @DisplayName("a wrong answer comes back in ten minutes")
    void wrongAnswerReturnsSoon() {
        CardProgress c = card();
        c.record(false, NOW);

        assertThat(c.isDue(NOW.plus(9, ChronoUnit.MINUTES))).isFalse();
        assertThat(c.isDue(NOW.plus(11, ChronoUnit.MINUTES))).isTrue();
    }

    @Test
    @DisplayName("a wrong answer drops all the way to box 1, not one box")
    void wrongAnswerDemotesToTheBottom() {
        CardProgress c = card();
        c.record(true, NOW);
        c.record(true, NOW);
        c.record(true, NOW);
        assertThat(c.getBox()).isEqualTo(4);

        c.record(false, NOW);
        // Demoting by one would keep showing a card you have never learned at
        // week-long intervals. The asymmetry is the point of the algorithm.
        assertThat(c.getBox()).isEqualTo(1);
        assertThat(c.getLastResult()).isEqualTo(CardProgress.Result.WRONG);
    }

    @Test
    @DisplayName("never climbs past the top box")
    void boxIsCapped() {
        CardProgress c = card();
        for (int i = 0; i < 20; i++) {
            c.record(true, NOW);
        }
        assertThat(c.getBox()).isEqualTo(CardProgress.TOP_BOX);
        assertThat(c.strength()).isEqualTo(1.0);
        assertThat(c.isKnown()).isTrue();
    }

    @Test
    @DisplayName("counts every attempt, right or wrong")
    void countsAttempts() {
        CardProgress c = card();
        c.record(true, NOW);
        c.record(false, NOW);
        c.record(true, NOW);
        assertThat(c.getTimesSeen()).isEqualTo(3);
    }

    @Test
    @DisplayName("restore clamps a box that arrived out of range")
    void restoreClamps() {
        CardProgress c = card();
        // The client is a browser and its numbers are input, not facts.
        c.restore(99, -4, CardProgress.Result.RIGHT, NOW, NOW);
        assertThat(c.getBox()).isEqualTo(CardProgress.TOP_BOX);
        assertThat(c.getTimesSeen()).isZero();

        c.restore(-1, 3, null, null, null);
        assertThat(c.getBox()).isEqualTo(1);
    }
}
