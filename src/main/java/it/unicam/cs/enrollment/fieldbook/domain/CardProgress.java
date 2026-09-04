package it.unicam.cs.enrollment.fieldbook.domain;

import it.unicam.cs.enrollment.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The scheduling state of one question, for one learner: which Leitner box it
 * is in, and when it is next due.
 *
 * <h2>The algorithm, in three sentences</h2>
 * Every card lives in a numbered box. Answer it correctly and it moves up one
 * box and disappears for that box's interval; get it wrong and it drops all the
 * way back to box 1 and returns in ten minutes. That is the whole of the
 * Leitner system, and it is the part of spaced repetition that does most of the
 * work - the elaborate schedulers (SM-2, FSRS) tune the intervals, they do not
 * change the idea.
 *
 * <p>The reason it beats rereading is the TESTING EFFECT: trying to retrieve an
 * answer, and especially failing to, strengthens the memory far more than
 * seeing the answer again does. The spacing is the second effect: each
 * successful retrieval at a longer delay buys a longer one still.
 *
 * <h2>Why the identity of a card is its text, not its position</h2>
 * {@code cardKey} is a hash of the question wording, produced by the browser.
 * Numbering the questions instead would mean that inserting a question at
 * position 12 silently reassigns every learner's history from 12 onwards - they
 * would find themselves "already knowing" a card they have never seen. Content
 * addressing avoids that: change the wording and it becomes a genuinely new
 * card, which is the correct behaviour, because a reworded question IS a new
 * question.
 *
 * <p>The trade-off is that fixing a typo resets that card for everybody. That
 * is a real cost and the right one to pay - the alternative silently corrupts
 * data instead of visibly losing a little.
 */
@Entity
@Table(
        name = "fieldbook_cards",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_fieldbook_cards_account_key",
                columnNames = {"account_id", "card_key"}),
        indexes = @Index(name = "idx_fb_cards_account_due", columnList = "account_id, due_at")
)
@NamedQuery(
        name = "CardProgress.findByAccount",
        query = "SELECT c FROM CardProgress c WHERE c.account = :account"
)
@NamedQuery(
        name = "CardProgress.findByAccountAndKeys",
        query = "SELECT c FROM CardProgress c WHERE c.account = :account AND c.cardKey IN :keys"
)
@NamedQuery(
        name = "CardProgress.deleteForAccount",
        query = "DELETE FROM CardProgress c WHERE c.account = :account"
)
public class CardProgress extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * Box 0 is unused so that the box number reads as "how many times in a row
     * you have got this right, capped". Index 1..5 are the live boxes.
     *
     * <p>These intervals are the ones the browser has always used, restated on
     * the server so that a synced account and an offline one behave
     * identically. Two implementations of one rule is a smell; the honest fix
     * is that the server is authoritative and the browser copy exists only so
     * the page works logged out. That is written down here so nobody "tidies
     * up" by making them differ.
     */
    private static final Duration[] INTERVAL = {
            Duration.ZERO,
            Duration.ofMinutes(10),
            Duration.ofDays(1),
            Duration.ofDays(3),
            Duration.ofDays(7),
            Duration.ofDays(21)
    };

    /** A card in this box or above counts as known. */
    public static final int KNOWN_BOX = 4;

    public static final int TOP_BOX = 5;

    /** How the last attempt went. Null until the card has been answered once. */
    public enum Result { RIGHT, WRONG }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fb_cards_account"))
    private LearnerAccount account;

    /**
     * {@code <bank>:<hash>} - for example {@code interview:1k2j9f}. The bank
     * prefix keeps the three question sets from colliding and lets the server
     * report per-bank statistics without knowing anything about the questions.
     */
    @Column(name = "card_key", nullable = false, length = 80, updatable = false)
    private String cardKey;

    /**
     * Which chapter this card belongs to, as a slug such as {@code ch-persistence}.
     * Denormalised on purpose: it is a property of the question, not of the
     * learner, but storing it here is what lets "mastery of chapter 8" be a
     * single grouped query instead of a join against a question catalogue the
     * server does not have. The questions live in the HTML; the server
     * deliberately does not own them.
     */
    @Column(name = "chapter_id", length = 60)
    private String chapterId;

    @Column(name = "box", nullable = false)
    private int box;

    @Column(name = "times_seen", nullable = false)
    private int timesSeen;

    /**
     * {@code EnumType.STRING}, never {@code ORDINAL}. Ordinal stores the
     * position in the declaration, so reordering the enum silently rewrites the
     * meaning of every existing row. It is the single most expensive one-word
     * mistake in JPA.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_result", length = 10)
    private Result lastResult;

    @Column(name = "due_at")
    private Instant dueAt;

    /**
     * When this card was last answered, as far as conflict resolution is
     * concerned.
     *
     * <h3>Why this is not just {@code updatedAt} from the base class</h3>
     * Because they answer different questions. {@code updatedAt} is an AUDIT
     * column: it says when the row was last written, it is maintained by a JPA
     * lifecycle callback, and it is therefore null until the row is flushed.
     * This field is a MERGE CLOCK: it says when the learner last answered the
     * question, on whichever device they were holding, and the sync compares it
     * against the same value from the other copy.
     *
     * <p>Borrowing the audit column for the merge looks like a saving of one
     * column and costs you a subtle bug: a row created earlier in the same
     * transaction has no {@code updatedAt} yet, so the comparison silently
     * degrades to "the client always wins". It also means that any write for
     * any reason - a background fix-up, a re-save - would move the merge clock
     * and beat a genuine answer from another device.
     *
     * <p>The general lesson is worth more than the column: when one field is
     * asked to mean two things, the two meanings eventually disagree, and the
     * disagreement shows up as data loss rather than as a compile error.
     */
    @Column(name = "synced_at")
    private Instant syncedAt;

    protected CardProgress() {
        // required by JPA
    }

    private CardProgress(LearnerAccount account, String cardKey, String chapterId) {
        this.account = account;
        this.cardKey = cardKey;
        this.chapterId = chapterId;
        this.box = 1;
    }

    public static CardProgress start(LearnerAccount account, String cardKey, String chapterId) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(cardKey, "cardKey must not be null");
        return new CardProgress(account, cardKey, chapterId);
    }

    /**
     * Apply one answer. Up a box on success, straight back to box 1 on failure.
     *
     * <p>The asymmetry is the point: progress is earned one step at a time and
     * lost all at once. A scheme that only demoted one box would keep showing
     * you a card you have never actually learned at week-long intervals.
     */
    public void record(boolean correct, Instant now) {
        this.box = correct ? Math.min(TOP_BOX, this.box + 1) : 1;
        this.lastResult = correct ? Result.RIGHT : Result.WRONG;
        this.timesSeen = this.timesSeen + 1;
        this.dueAt = now.plus(INTERVAL[this.box]);
        this.syncedAt = now;
    }

    public boolean isDue(Instant now) {
        return dueAt == null || !dueAt.isAfter(now);
    }

    public boolean isKnown() {
        return box >= KNOWN_BOX;
    }

    /**
     * How far through the boxes this card is, from 0 to 1. Used to turn a pile
     * of boxes into the single mastery percentage the sidebar shows.
     *
     * <p>A card in box 1 scores 0 rather than 0.2: being at the bottom of the
     * ladder is not partial knowledge, it is where you start.
     */
    public double strength() {
        return Math.max(0, box - 1) / (double) (TOP_BOX - 1);
    }

    public LearnerAccount getAccount() {
        return account;
    }

    public String getCardKey() {
        return cardKey;
    }

    public String getChapterId() {
        return chapterId;
    }

    public void setChapterId(String chapterId) {
        this.chapterId = chapterId;
    }

    public int getBox() {
        return box;
    }

    /**
     * Used only when merging state that was recorded offline - see
     * {@code ProgressService}. Normal code paths go through {@link #record}.
     */
    public void restore(int box, int timesSeen, Result lastResult, Instant dueAt, Instant syncedAt) {
        this.box = Math.max(1, Math.min(TOP_BOX, box));
        this.timesSeen = Math.max(0, timesSeen);
        this.lastResult = lastResult;
        this.dueAt = dueAt;
        this.syncedAt = syncedAt;
    }

    public int getTimesSeen() {
        return timesSeen;
    }

    public Result getLastResult() {
        return lastResult;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }
}
