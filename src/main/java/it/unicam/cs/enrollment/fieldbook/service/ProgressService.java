package it.unicam.cs.enrollment.fieldbook.service;

import it.unicam.cs.enrollment.common.logging.Loggable;
import it.unicam.cs.enrollment.fieldbook.domain.CardProgress;
import it.unicam.cs.enrollment.fieldbook.domain.ChapterProgress;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.fieldbook.repository.LearnerAccountRepository;
import it.unicam.cs.enrollment.fieldbook.repository.ProgressRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes a learner's study record.
 *
 * <h2>The synchronisation problem, stated honestly</h2>
 * The page works with no account at all - everything lives in
 * {@code localStorage}. Signing in does not switch that off; it adds a server
 * copy. So there are two copies of the same data being edited independently,
 * which is distributed systems in miniature and has no free answer.
 *
 * <p>The policy here is LAST WRITE WINS, per item, on the item's own
 * {@code updatedAt}. It is chosen for one property: it is easy to explain and
 * therefore easy to predict. Its cost is equally plain - study on your phone,
 * then on a laptop that has been offline since yesterday, and the laptop's
 * older answer for that specific card is discarded. Not the whole record; one
 * card.
 *
 * <p>What makes it tolerable is the shape of the data. Progress is
 * near-monotonic: boxes go up far more often than down, and the merge below
 * takes the higher box when the two sides disagree within the same second, so
 * the common conflict resolves in the direction that does not lose work. The
 * genuinely correct answers - vector clocks, or CRDTs, where a counter merges
 * by construction - are a lot of machinery for a study tool. Knowing which one
 * you have chosen, and what it costs, is the part an interviewer is testing.
 *
 * <p>Note also what is NOT synced: nothing is ever deleted by a sync. A card
 * the client does not mention is left alone rather than removed, because "not
 * mentioned" and "deleted" are indistinguishable over a lossy connection, and
 * guessing wrong destroys data. Deletion is its own explicit endpoint.
 */
@Loggable
@ApplicationScoped
public class ProgressService {

    private ProgressRepository progress;
    private LearnerAccountRepository accountRepository;
    private MasteryCalculator mastery;
    private AccountService accounts;
    private Clock clock;
    private Logger log;

    /**
     * Required by CDI, and the reason is worth knowing because the error it
     * produces names neither this constructor nor the annotation that needs it.
     *
     * <p>An {@code @ApplicationScoped} bean is NORMAL SCOPED, so what gets
     * injected anywhere is never this object - it is a generated PROXY
     * subclass that forwards to the contextual instance. To generate that
     * subclass the container has to be able to instantiate it, and a subclass
     * can only be instantiated through a superclass constructor it can call
     * with no arguments.
     *
     * <p>Leave it out and the deployment fails, not the compile:
     * <pre>WELD-001435: Normal scoped bean class ... is not proxyable
     * because it has no no-args constructor</pre>
     * reported against whatever injected it rather than against this class.
     *
     * <p>It also forces the fields below to be non-final, since this
     * constructor leaves them unset. That is the cost of the proxy, it is why
     * every service in this codebase looks like this, and it is the concrete
     * form of the point the fieldbook makes about why the container avoids
     * your constructors.
     */
    protected ProgressService() {
        // required by CDI
    }

    @Inject
    public ProgressService(ProgressRepository progress,
                           LearnerAccountRepository accountRepository,
                           MasteryCalculator mastery,
                           AccountService accounts,
                           Clock clock,
                           Logger log) {
        this.progress = progress;
        this.accountRepository = accountRepository;
        this.mastery = mastery;
        this.accounts = accounts;
        this.clock = clock;
        this.log = log;
    }

    /** One card as the browser sees it. */
    public static final class CardState {
        public String key;
        public String chapterId;
        public int box;
        public int seen;
        public String last;
        public Instant dueAt;
        public Instant updatedAt;
    }

    /** One chapter as the browser sees it. */
    public static final class ChapterState {
        public String chapterId;
        public Instant readAt;
        public int bestScore;
        public int attempts;
        public Instant passedAt;
        public Instant updatedAt;
    }

    /** Everything the page needs to draw itself after a sync. */
    public static final class Snapshot {
        public MasteryCalculator.CourseMastery mastery;
        public int streak;
        public int bestStreak;
        public List<CardState> cards = new ArrayList<>();
        public List<ChapterState> chapters = new ArrayList<>();
        public List<String> studyDays = new ArrayList<>();
    }

    /**
     * Merge what the browser has into what the server has, then hand back the
     * merged result.
     *
     * <p>One round trip rather than a push and a pull. That is not only fewer
     * requests: it makes the whole exchange atomic from the client's point of
     * view, so there is no window in which the two sides have both half applied
     * each other's changes.
     */
    @Transactional
    public Snapshot sync(LearnerAccount caller,
                         List<String> catalogue,
                         Collection<String> withCheckpoint,
                         List<CardState> incomingCards,
                         List<ChapterState> incomingChapters) {

        LearnerAccount account = lock(caller);
        mergeCards(account, incomingCards);
        mergeChapters(account, incomingChapters);

        if (hasActivity(incomingCards, incomingChapters)) {
            // The locked instance is managed, so this writes directly rather
            // than going back through AccountService to re-load it.
            account.recordStudyDay(accounts.todayFor(account));
        }

        return snapshot(account, catalogue, withCheckpoint);
    }

    /** Read-only view, used on page load before anything has been answered. */
    @Transactional
    public Snapshot snapshot(LearnerAccount account,
                             List<String> catalogue,
                             Collection<String> withCheckpoint) {

        List<CardProgress> cards = progress.cardsFor(account);
        List<ChapterProgress> chapters = progress.chaptersFor(account);

        Map<String, ChapterProgress> byChapter = new LinkedHashMap<>();
        for (ChapterProgress c : chapters) {
            byChapter.put(c.getChapterId(), c);
        }

        Snapshot out = new Snapshot();
        out.mastery = mastery.forCourse(
                catalogue == null ? Collections.emptyList() : catalogue,
                withCheckpoint == null ? Collections.emptySet() : withCheckpoint,
                byChapter,
                mastery.groupByChapter(cards));
        out.streak = accounts.currentStreak(account);
        out.bestStreak = account.getBestStreak();

        for (CardProgress c : cards) {
            CardState s = new CardState();
            s.key = c.getCardKey();
            s.chapterId = c.getChapterId();
            s.box = c.getBox();
            s.seen = c.getTimesSeen();
            s.last = c.getLastResult() == null ? null
                    : c.getLastResult().name().toLowerCase(java.util.Locale.ROOT);
            s.dueAt = c.getDueAt();
            s.updatedAt = c.getSyncedAt();
            out.cards.add(s);
        }

        for (ChapterProgress c : chapters) {
            ChapterState s = new ChapterState();
            s.chapterId = c.getChapterId();
            s.readAt = c.getReadAt();
            s.bestScore = c.getBestScore();
            s.attempts = c.getAttempts();
            s.passedAt = c.getPassedAt();
            s.updatedAt = c.getUpdatedAt() == null ? c.getCreatedAt() : c.getUpdatedAt();
            out.chapters.add(s);
        }

        // Not account.studyDaysSorted(): that walks a lazy collection on an
        // entity which is usually detached by the time this runs.
        for (java.time.LocalDate d : accounts.studyDays(account)) {
            out.studyDays.add(d.toString());
        }
        return out;
    }

    /**
     * Record one checkpoint attempt directly, without a full sync.
     *
     * @return true when this attempt passed the chapter for the first time
     */
    @Transactional
    public boolean recordCheckpoint(LearnerAccount caller, String chapterId, int score) {
        Instant now = clock.instant();
        LearnerAccount account = lock(caller);
        ChapterProgress cp = findOrStartChapter(account, chapterId);
        boolean firstPass = cp.recordAttempt(score, now);
        account.recordStudyDay(accounts.todayFor(account));
        if (firstPass) {
            log.info("Account id={} passed chapter {} with {}%", account.getId(), chapterId, score);
        }
        return firstPass;
    }

    @Transactional
    public void markRead(LearnerAccount caller, String chapterId) {
        findOrStartChapter(lock(caller), chapterId).markRead(clock.instant());
    }

    /**
     * Take a row lock on the learner's own account, and return the managed
     * instance.
     *
     * <h3>Why this is needed at all</h3>
     * Every write below is a read-then-insert: look for a card with this key,
     * and create it if it is not there. Two of those running at once both see
     * nothing and both insert, and the unique constraint refuses the second -
     * so one of a learner's own two open tabs gets a 500. The same race exists
     * three times over here, on the card key, on the chapter id and on the
     * study day, plus a fourth on the account's version column.
     *
     * <p>Rather than defend against each of them separately, this takes one
     * {@code SELECT ... FOR UPDATE} on the account and lets the rest of the
     * method assume it is alone. It is exactly the pattern
     * {@code EnrollmentService} uses to count the last seat, applied to the
     * same shape of problem: a sequence of read-decide-write that must not be
     * interleaved.
     *
     * <h3>What it costs</h3>
     * Two syncs for the same learner queue behind one another. That is the
     * correct behaviour - they are editing the same rows - and it costs nothing
     * across learners, because the lock is per account. It is also why the
     * lock is taken on the ACCOUNT and not on anything global: contention
     * scoped to one person's own devices is not contention.
     *
     * <p>The alternative worth naming in an interview is an upsert -
     * PostgreSQL's {@code INSERT ... ON CONFLICT DO UPDATE} - which needs no
     * lock at all and is not expressible in portable JPQL. That is the right
     * answer when throughput matters more than portability.
     */
    private LearnerAccount lock(LearnerAccount caller) {
        // Lock first, in its own statement, then read. Doing it the other way
        // round - or letting em.find take the lock - reintroduces the race,
        // because on this entity the locking find degrades into a read
        // followed by a separate lock. See LearnerAccountRepository.lockRow.
        accountRepository.lockRow(caller.getId());
        return accountRepository.findById(caller.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated account " + caller.getId() + " no longer exists"));
    }

    @Transactional
    public int reset(LearnerAccount account) {
        int wiped = progress.resetFor(account);
        log.info("Reset {} progress rows for account id={}", wiped, account.getId());
        return wiped;
    }

    // ------------------------------------------------------------- internals

    private void mergeCards(LearnerAccount account, List<CardState> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        Map<String, CardState> wanted = new LinkedHashMap<>();
        for (CardState s : incoming) {
            if (s != null && s.key != null && !s.key.isEmpty()) {
                wanted.put(s.key, s);
            }
        }
        // One query for every key the client mentioned, not one per key. The
        // loop-with-a-findById version of this method is the N+1 problem, and a
        // sync of four hundred cards is where you would feel it.
        Map<String, CardProgress> existing = new HashMap<>();
        for (CardProgress c : progress.cardsFor(account, wanted.keySet())) {
            existing.put(c.getCardKey(), c);
        }

        for (Map.Entry<String, CardState> e : wanted.entrySet()) {
            CardState s = e.getValue();
            CardProgress row = existing.get(e.getKey());
            if (row == null) {
                row = CardProgress.start(account, e.getKey(), s.chapterId);
                row.restore(s.box, s.seen, parseResult(s.last), s.dueAt, s.updatedAt);
                progress.add(row);
                continue;
            }
            if (row.getChapterId() == null && s.chapterId != null) {
                row.setChapterId(s.chapterId);
            }
            if (clientWins(s.updatedAt, row.getSyncedAt(), row.getBox(), s.box)) {
                row.restore(s.box, Math.max(s.seen, row.getTimesSeen()),
                        parseResult(s.last), s.dueAt, s.updatedAt);
            }
        }
    }

    private void mergeChapters(LearnerAccount account, List<ChapterState> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        Map<String, ChapterProgress> existing = new HashMap<>();
        for (ChapterProgress c : progress.chaptersFor(account)) {
            existing.put(c.getChapterId(), c);
        }

        for (ChapterState s : incoming) {
            if (s == null || s.chapterId == null || s.chapterId.isEmpty()) {
                continue;
            }
            ChapterProgress row = existing.get(s.chapterId);
            if (row == null) {
                row = ChapterProgress.start(account, s.chapterId);
                row.restore(s.readAt, s.bestScore, s.attempts, s.passedAt);
                progress.add(row);
                continue;
            }
            // Chapters merge by taking the better of each field rather than by
            // timestamp, because every field here is monotonic in the same
            // direction: you cannot un-read a chapter, and the best score is a
            // maximum by definition. Where a field can only improve, "take the
            // maximum" is a conflict-free merge and needs no clock at all.
            row.restore(
                    earliest(row.getReadAt(), s.readAt),
                    Math.max(row.getBestScore(), s.bestScore),
                    Math.max(row.getAttempts(), s.attempts),
                    earliest(row.getPassedAt(), s.passedAt));
        }
    }

    /**
     * The tie-breaker.
     *
     * <p>Strictly later wins. Where the two sides cannot be ordered - an exact
     * tie, or a copy with no clock at all - the higher box wins, so the
     * unresolvable case resolves towards keeping progress rather than losing
     * it. Two devices syncing within the same second is exactly when this
     * matters, and it is more common than it sounds because both are usually
     * reacting to the same person picking their phone up.
     *
     * <p>A client that sends no timestamp is not trusted to overwrite anything
     * on the strength of that alone, which is why the same "higher box wins"
     * rule covers it. Treating missing data as newest is how a buggy old client
     * quietly wipes a good record.
     */
    private static boolean clientWins(Instant clientAt, Instant serverAt,
                                      int serverBox, int clientBox) {
        if (clientAt == null || serverAt == null || clientAt.equals(serverAt)) {
            return clientBox > serverBox;
        }
        return clientAt.isAfter(serverAt);
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private ChapterProgress findOrStartChapter(LearnerAccount account, String chapterId) {
        for (ChapterProgress c : progress.chaptersFor(account)) {
            if (c.getChapterId().equals(chapterId)) {
                return c;
            }
        }
        ChapterProgress fresh = ChapterProgress.start(account, chapterId);
        progress.add(fresh);
        return fresh;
    }

    private static CardProgress.Result parseResult(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("RIGHT".equals(v)) {
            return CardProgress.Result.RIGHT;
        }
        if ("WRONG".equals(v)) {
            return CardProgress.Result.WRONG;
        }
        // Unknown values are dropped rather than thrown on. The field comes
        // from a browser; an old client sending a value this build does not
        // know about should not fail the whole sync.
        return null;
    }

    private static boolean hasActivity(List<CardState> cards, List<ChapterState> chapters) {
        return (cards != null && !cards.isEmpty()) || (chapters != null && !chapters.isEmpty());
    }
}
