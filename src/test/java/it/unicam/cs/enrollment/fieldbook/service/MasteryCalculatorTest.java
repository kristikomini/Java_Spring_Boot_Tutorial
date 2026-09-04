package it.unicam.cs.enrollment.fieldbook.service;

import it.unicam.cs.enrollment.domain.model.Email;
import it.unicam.cs.enrollment.fieldbook.domain.CardProgress;
import it.unicam.cs.enrollment.fieldbook.domain.ChapterProgress;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The percentage in the sidebar. Worth testing carefully because it is the
 * number the reader will trust, and a subtly wrong one is worse than none.
 */
@DisplayName("MasteryCalculator")
class MasteryCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final MasteryCalculator calc = new MasteryCalculator();

    private LearnerAccount account() {
        return LearnerAccount.register(Email.of("m@u.it"), "M", "hash", null);
    }

    private ChapterProgress chapter(String id, boolean read, int score) {
        ChapterProgress cp = ChapterProgress.start(account(), id);
        if (read) {
            cp.markRead(NOW);
        }
        if (score > 0) {
            cp.recordAttempt(score, NOW);
        }
        return cp;
    }

    private List<CardProgress> cardsInBox(String chapterId, int box, int count) {
        List<CardProgress> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CardProgress c = CardProgress.start(account(), "quiz:" + chapterId + i, chapterId);
            c.restore(box, 1, CardProgress.Result.RIGHT, NOW, NOW);
            out.add(c);
        }
        return out;
    }

    @Test
    @DisplayName("an untouched chapter is zero")
    void untouched() {
        assertThat(calc.forChapter("ch-a", null, Collections.emptyList(), true).getPercent())
                .isZero();
    }

    @Test
    @DisplayName("reading alone is worth the reading weight and no more")
    void readingIsWorthLittle() {
        // 0.15 earned out of 0.60 available (read + checkpoint, no cards yet).
        int percent = calc.forChapter("ch-a", chapter("ch-a", true, 0),
                Collections.emptyList(), true).getPercent();
        assertThat(percent).isEqualTo(25);
    }

    @Test
    @DisplayName("a perfect checkpoint still leaves the spaced cards to do")
    void checkpointAloneIsNotMastery() {
        int percent = calc.forChapter("ch-a", chapter("ch-a", true, 100),
                Collections.emptyList(), true).getPercent();
        // Everything available so far is earned, but no card has been answered,
        // so the card component is not in the denominator yet either.
        assertThat(percent).isEqualTo(100);

        // The moment a card exists and is at the bottom box, the picture is
        // honest again: the reader has demonstrated nothing after a delay.
        int withCards = calc.forChapter("ch-a", chapter("ch-a", true, 100),
                cardsInBox("ch-a", 1, 4), true).getPercent();
        assertThat(withCards).isEqualTo(60);
    }

    @Test
    @DisplayName("cards at the top box complete the chapter")
    void everythingEarned() {
        int percent = calc.forChapter("ch-a", chapter("ch-a", true, 100),
                cardsInBox("ch-a", 5, 3), true).getPercent();
        assertThat(percent).isEqualTo(100);
    }

    @Test
    @DisplayName("a chapter with no checkpoint is scored out of what it offers")
    void weightsAreRedistributed() {
        // Without redistribution this chapter could never exceed 55% and the
        // course total could never reach 100, which reads as a broken bar.
        int percent = calc.forChapter("ch-cheat-sheet", chapter("ch-cheat-sheet", true, 0),
                cardsInBox("ch-cheat-sheet", 5, 2), false).getPercent();
        assertThat(percent).isEqualTo(100);
    }

    @Test
    @DisplayName("the course percentage is the mean of its chapters")
    void courseIsTheMean() {
        List<String> catalogue = Arrays.asList("ch-a", "ch-b");
        Map<String, ChapterProgress> state = new HashMap<>();
        state.put("ch-a", chapter("ch-a", true, 100));

        Map<String, List<CardProgress>> cards = new HashMap<>();
        cards.put("ch-a", cardsInBox("ch-a", 5, 2));

        MasteryCalculator.CourseMastery course =
                calc.forCourse(catalogue, Arrays.asList("ch-a", "ch-b"), state, cards);

        assertThat(course.getPercent()).isEqualTo(50);
        assertThat(course.getChaptersTotal()).isEqualTo(2);
        assertThat(course.getChaptersRead()).isEqualTo(1);
        assertThat(course.getChaptersPassed()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty catalogue is zero rather than a divide by zero")
    void emptyCatalogue() {
        MasteryCalculator.CourseMastery course = calc.forCourse(
                Collections.emptyList(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap());
        assertThat(course.getPercent()).isZero();
    }

    @Test
    @DisplayName("grouping drops cards that do not name a chapter")
    void groupingIgnoresUnattributedCards() {
        List<CardProgress> cards = new ArrayList<>(cardsInBox("ch-a", 3, 2));
        cards.add(CardProgress.start(account(), "interview:abc", null));

        Map<String, List<CardProgress>> grouped = calc.groupByChapter(cards);
        assertThat(grouped).containsOnlyKeys("ch-a");
        assertThat(grouped.get("ch-a")).hasSize(2);
    }

    @Test
    @DisplayName("a chapter passes only at or above the pass mark")
    void passMark() {
        ChapterProgress cp = ChapterProgress.start(account(), "ch-a");
        assertThat(cp.recordAttempt(ChapterProgress.PASS_MARK - 1, NOW)).isFalse();
        assertThat(cp.isPassed()).isFalse();

        assertThat(cp.recordAttempt(ChapterProgress.PASS_MARK, NOW)).isTrue();
        assertThat(cp.isPassed()).isTrue();

        // Passing twice is not passing for the first time twice.
        assertThat(cp.recordAttempt(100, NOW)).isFalse();
        assertThat(cp.getAttempts()).isEqualTo(3);
        assertThat(cp.getBestScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("keeps the best score, not the last")
    void bestScoreSurvivesAWorseRetake() {
        ChapterProgress cp = ChapterProgress.start(account(), "ch-a");
        cp.recordAttempt(100, NOW);
        cp.recordAttempt(20, NOW);
        assertThat(cp.getBestScore()).isEqualTo(100);
    }
}
