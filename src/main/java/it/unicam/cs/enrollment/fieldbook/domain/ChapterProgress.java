package it.unicam.cs.enrollment.fieldbook.domain;

import it.unicam.cs.enrollment.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

/**
 * One learner's standing in one chapter: whether they have read it, and how
 * their end-of-chapter checkpoint went.
 *
 * <h2>Why "read" is a weak signal and is weighted as one</h2>
 * The page marks a chapter read when the reader has actually scrolled to the
 * end of it, which is a better proxy than "clicked the link" and still a bad
 * proxy for "understood it". Judging your own understanding from having read
 * something is the best documented illusion in the whole of study skills: the
 * text feels familiar, and familiarity gets mistaken for recall. That is why
 * reading is a small slice of the mastery number in {@code MasteryCalculator}
 * and retrieval is most of it.
 *
 * <h2>Why the best score is kept rather than the last</h2>
 * Because the checkpoint is there to be retaken. Storing the last score would
 * punish a reader for going back to a chapter to check something, which is
 * exactly the behaviour the whole design is trying to encourage. Attempts are
 * counted separately so the number is not mistaken for a first-time pass rate.
 */
@Entity
@Table(
        name = "fieldbook_chapters",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_fieldbook_chapters_account_chapter",
                columnNames = {"account_id", "chapter_id"})
)
@NamedQuery(
        name = "ChapterProgress.findByAccount",
        query = "SELECT c FROM ChapterProgress c WHERE c.account = :account"
)
@NamedQuery(
        name = "ChapterProgress.deleteForAccount",
        query = "DELETE FROM ChapterProgress c WHERE c.account = :account"
)
public class ChapterProgress extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * The bar a checkpoint has to clear to count as passed.
     *
     * <p>Every checkpoint in the fieldbook has at least four questions, so 75%
     * is exactly "at most one wrong". That is a bar you can clear having
     * misread something once, and cannot clear by guessing, since four options
     * give you 25% a question.
     *
     * <p>80% was the first choice and was wrong for a reason worth recording:
     * on a four-question checkpoint it demands a perfect score, which turns a
     * retrieval exercise into an exam. A pass mark is a product decision about
     * the number of questions, not a round number picked in advance - and the
     * two have to be chosen together.
     *
     * <p>The browser holds the same constant, because the page has to work with
     * no server. Two copies of one rule is a smell, and the mitigation is that
     * both say so and name each other.
     */
    public static final int PASS_MARK = 75;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fb_chapters_account"))
    private LearnerAccount account;

    /** The chapter slug, for example {@code ch-persistence}. */
    @Column(name = "chapter_id", nullable = false, length = 60, updatable = false)
    private String chapterId;

    @Column(name = "read_at")
    private Instant readAt;

    /** Best checkpoint score so far, 0-100. */
    @Column(name = "best_score", nullable = false)
    private int bestScore;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "passed_at")
    private Instant passedAt;

    protected ChapterProgress() {
        // required by JPA
    }

    private ChapterProgress(LearnerAccount account, String chapterId) {
        this.account = account;
        this.chapterId = chapterId;
    }

    public static ChapterProgress start(LearnerAccount account, String chapterId) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        return new ChapterProgress(account, chapterId);
    }

    /** Idempotent: the first time you finish a chapter is the one that is recorded. */
    public void markRead(Instant now) {
        if (this.readAt == null) {
            this.readAt = now;
        }
    }

    /**
     * Record a checkpoint attempt.
     *
     * @param score 0-100
     * @return true if this attempt was the one that first passed the chapter,
     *         which is what the page turns into a visible milestone. Returning
     *         it from here rather than recomputing it in the caller keeps the
     *         "first time" rule in one place.
     */
    public boolean recordAttempt(int score, Instant now) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100, was " + score);
        }
        this.attempts = this.attempts + 1;
        if (score > this.bestScore) {
            this.bestScore = score;
        }
        boolean firstPass = this.passedAt == null && score >= PASS_MARK;
        if (firstPass) {
            this.passedAt = now;
        }
        return firstPass;
    }

    /** Offline merge only. See {@code ProgressService}. */
    public void restore(Instant readAt, int bestScore, int attempts, Instant passedAt) {
        this.readAt = readAt;
        this.bestScore = Math.max(0, Math.min(100, bestScore));
        this.attempts = Math.max(0, attempts);
        this.passedAt = passedAt;
    }

    public boolean isPassed() {
        return passedAt != null;
    }

    public boolean isRead() {
        return readAt != null;
    }

    public LearnerAccount getAccount() {
        return account;
    }

    public String getChapterId() {
        return chapterId;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public int getBestScore() {
        return bestScore;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getPassedAt() {
        return passedAt;
    }
}
