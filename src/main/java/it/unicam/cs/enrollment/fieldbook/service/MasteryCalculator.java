package it.unicam.cs.enrollment.fieldbook.service;

import it.unicam.cs.enrollment.fieldbook.domain.CardProgress;
import it.unicam.cs.enrollment.fieldbook.domain.ChapterProgress;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns "read a chapter, passed a quiz, answered some flashcards" into the one
 * number the sidebar shows.
 *
 * <h2>Why the number is weighted the way it is</h2>
 * A progress bar that fills up as you scroll measures scrolling. This one is
 * built so that the only way to move it far is to demonstrate recall, because
 * the whole point of the exercise is that recall and familiarity feel identical
 * from the inside and are not the same thing.
 *
 * <pre>
 *   reading the chapter        15%   weak evidence, but not none
 *   the end-of-chapter check   45%   answered once, with the chapter fresh
 *   the spaced cards           40%   answered again, days later
 * </pre>
 *
 * <p>The split between the last two is the important one. A checkpoint taken
 * immediately after reading is mostly a test of short-term memory - it is worth
 * having, because being wrong straight away is the fastest possible correction,
 * but passing it does not mean you will still know the answer next week. The
 * card component can only rise by answering the same material correctly after a
 * delay, and it decays back down the moment you get one wrong. So the number
 * cannot be finished in an afternoon, and it is not supposed to be.
 *
 * <p>Reading is worth something rather than nothing because a chapter you have
 * never opened and a chapter you have read but not yet been tested on are
 * genuinely different states, and a bar that ignores the difference tells a new
 * reader nothing for their first hour.
 *
 * <h2>Chapters with no questions</h2>
 * A few chapters - the cheat sheet, the run-it-yourself instructions - have no
 * checkpoint. Scoring those out of a denominator they cannot reach would cap
 * the course at something under 100% forever, which reads as broken. Their
 * weights are redistributed instead, so a chapter is always scored out of what
 * it actually offers.
 *
 * <h2>Why this class has no dependencies</h2>
 * It is a pure function of its arguments: same input, same output, no clock, no
 * database, no injection. That makes it trivially testable, which for the piece
 * of logic every learner will stare at is worth arranging deliberately. Notice
 * how much easier this is to test than it would be if it reached for the
 * repository itself - that difference is most of what "dependency injection
 * improves testability" actually means in practice.
 */
@ApplicationScoped
public class MasteryCalculator {

    static final double W_READ = 0.15;
    static final double W_CHECK = 0.45;
    static final double W_CARDS = 0.40;

    /** One chapter's standing. */
    public static final class ChapterMastery {

        private final String chapterId;
        private final int percent;
        private final boolean read;
        private final boolean passed;
        private final int bestScore;
        private final int cardsKnown;
        private final int cardsSeen;

        ChapterMastery(String chapterId, int percent, boolean read, boolean passed,
                       int bestScore, int cardsKnown, int cardsSeen) {
            this.chapterId = chapterId;
            this.percent = percent;
            this.read = read;
            this.passed = passed;
            this.bestScore = bestScore;
            this.cardsKnown = cardsKnown;
            this.cardsSeen = cardsSeen;
        }

        public String getChapterId() {
            return chapterId;
        }

        public int getPercent() {
            return percent;
        }

        public boolean isRead() {
            return read;
        }

        public boolean isPassed() {
            return passed;
        }

        public int getBestScore() {
            return bestScore;
        }

        public int getCardsKnown() {
            return cardsKnown;
        }

        public int getCardsSeen() {
            return cardsSeen;
        }
    }

    /** The course, and every chapter in it. */
    public static final class CourseMastery {

        private final int percent;
        private final int chaptersRead;
        private final int chaptersPassed;
        private final int chaptersTotal;
        private final List<ChapterMastery> chapters;

        CourseMastery(int percent, int chaptersRead, int chaptersPassed,
                      int chaptersTotal, List<ChapterMastery> chapters) {
            this.percent = percent;
            this.chaptersRead = chaptersRead;
            this.chaptersPassed = chaptersPassed;
            this.chaptersTotal = chaptersTotal;
            this.chapters = Collections.unmodifiableList(chapters);
        }

        public int getPercent() {
            return percent;
        }

        public int getChaptersRead() {
            return chaptersRead;
        }

        public int getChaptersPassed() {
            return chaptersPassed;
        }

        public int getChaptersTotal() {
            return chaptersTotal;
        }

        public List<ChapterMastery> getChapters() {
            return chapters;
        }
    }

    /**
     * @param catalogue      every chapter id in the course, in order. Supplied by
     *                       the page rather than stored here, because the
     *                       chapters live in the HTML and the server has no
     *                       business holding a second, drifting copy of the
     *                       course structure.
     * @param hasCheckpoint  which chapter ids actually offer a checkpoint
     * @param chapterState   what has been recorded per chapter
     * @param cardsByChapter the learner's cards, grouped by chapter
     */
    public CourseMastery forCourse(List<String> catalogue,
                                   Collection<String> hasCheckpoint,
                                   Map<String, ChapterProgress> chapterState,
                                   Map<String, List<CardProgress>> cardsByChapter) {
        List<ChapterMastery> out = new ArrayList<>();
        double total = 0;
        int read = 0;
        int passed = 0;

        for (String id : catalogue) {
            ChapterProgress cp = chapterState.get(id);
            List<CardProgress> cards = cardsByChapter.getOrDefault(id, Collections.emptyList());
            boolean checkpointExists = hasCheckpoint.contains(id);

            ChapterMastery m = forChapter(id, cp, cards, checkpointExists);
            out.add(m);
            total += m.getPercent();
            if (m.isRead()) {
                read++;
            }
            if (m.isPassed()) {
                passed++;
            }
        }

        int overall = catalogue.isEmpty() ? 0 : (int) Math.round(total / catalogue.size());
        return new CourseMastery(overall, read, passed, catalogue.size(), out);
    }

    /**
     * One chapter, out of 100.
     *
     * <p>The redistribution is done by summing only the weights that apply and
     * dividing by that sum, which is the general way to handle "this component
     * does not exist here" without a pile of special cases. Three components
     * become two, or one, and the arithmetic stays the same shape.
     */
    ChapterMastery forChapter(String chapterId, ChapterProgress state,
                              List<CardProgress> cards, boolean checkpointExists) {
        boolean readIt = state != null && state.isRead();
        boolean passedIt = state != null && state.isPassed();
        int best = state == null ? 0 : state.getBestScore();

        double earned = 0;
        double available = 0;

        available += W_READ;
        if (readIt) {
            earned += W_READ;
        }

        if (checkpointExists) {
            available += W_CHECK;
            earned += W_CHECK * (best / 100.0);
        }

        int known = 0;
        if (!cards.isEmpty()) {
            available += W_CARDS;
            double strength = 0;
            for (CardProgress c : cards) {
                strength += c.strength();
                if (c.isKnown()) {
                    known++;
                }
            }
            earned += W_CARDS * (strength / cards.size());
        }

        // available is never 0: W_READ is unconditional. Stated here because
        // "this cannot divide by zero" is exactly the kind of claim that stops
        // being true when somebody makes reading conditional too.
        int percent = (int) Math.round(100.0 * earned / available);
        return new ChapterMastery(chapterId, clamp(percent), readIt, passedIt, best, known, cards.size());
    }

    /**
     * Group a flat list of cards by chapter, dropping the ones that do not name
     * one.
     *
     * <p>A {@code LinkedHashMap} rather than a {@code HashMap} so the grouping
     * keeps the order it arrived in. It costs nothing and makes the output of
     * anything that iterates this map reproducible, which matters the first
     * time you diff two responses trying to work out what changed.
     */
    public Map<String, List<CardProgress>> groupByChapter(Collection<CardProgress> cards) {
        Map<String, List<CardProgress>> out = new LinkedHashMap<>();
        for (CardProgress c : cards) {
            String chapter = c.getChapterId();
            if (chapter == null || chapter.isEmpty()) {
                continue;
            }
            out.computeIfAbsent(chapter, k -> new ArrayList<>()).add(c);
        }
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }
}
