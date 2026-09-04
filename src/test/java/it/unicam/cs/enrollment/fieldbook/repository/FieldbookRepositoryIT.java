package it.unicam.cs.enrollment.fieldbook.repository;

import it.unicam.cs.enrollment.domain.model.Email;
import it.unicam.cs.enrollment.fieldbook.domain.AuthSession;
import it.unicam.cs.enrollment.fieldbook.domain.CardProgress;
import it.unicam.cs.enrollment.fieldbook.domain.ChapterProgress;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.fieldbook.domain.StickyNote;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fieldbook tables against a real SQL database.
 *
 * <p>Named {@code *IT} so Failsafe runs it during {@code mvn verify} rather
 * than Surefire during {@code mvn test} - the same split the enrollment
 * repository tests use. What it buys over the unit tests is everything JPA
 * actually does: whether the mappings are valid, whether the named queries
 * parse, whether the unique constraints are where the annotations claim, and
 * whether a lazy association behaves once there is a real persistence context
 * to be outside of.
 *
 * <p>H2 is not PostgreSQL, and the honest limits of that are set out in the
 * test persistence unit. A constraint violation here is a real bug; a
 * constraint violation that only happens on PostgreSQL will not be caught by
 * this file.
 */
@DisplayName("Fieldbook repositories (H2)")
class FieldbookRepositoryIT {

    private static EntityManagerFactory emf;
    private EntityManager em;

    private LearnerAccountRepository accounts;
    private AuthSessionRepository sessions;
    private ProgressRepository progress;
    private StickyNoteRepository notes;

    @BeforeAll
    static void bootPersistenceUnit() {
        emf = Persistence.createEntityManagerFactory("enrollmentTestPU");
    }

    @AfterAll
    static void closePersistenceUnit() {
        if (emf != null) {
            emf.close();
        }
    }

    @BeforeEach
    void setUp() {
        em = emf.createEntityManager();

        accounts = new LearnerAccountRepository();
        accounts.useEntityManager(em);
        sessions = new AuthSessionRepository();
        sessions.useEntityManager(em);
        notes = new StickyNoteRepository();
        notes.useEntityManager(em);
        progress = new ProgressRepository();
        progress.useEntityManager(em);

        em.getTransaction().begin();
    }

    @AfterEach
    void rollBack() {
        // Rolling back rather than deleting keeps every test independent
        // without a cleanup script, and without one test seeing another one
        // half-finished.
        if (em.getTransaction().isActive()) {
            em.getTransaction().rollback();
        }
        em.close();
    }

    private LearnerAccount account(String email) {
        LearnerAccount a = LearnerAccount.register(
                Email.of(email), "Mario", "pbkdf2-sha256$1$c2FsdA==$aGFzaA==", "Europe/Rome");
        return accounts.save(a);
    }

    @Test
    @DisplayName("stores an account and finds it by its normalised email")
    void findByEmail() {
        account("Mario.Rossi@Unicam.IT");
        em.flush();
        em.clear();

        // Email.of lower-cases on the way in, so this is the address that was
        // actually stored. Looking up the original capitalisation would find
        // nothing, which is the point of normalising at the boundary.
        Optional<LearnerAccount> found = accounts.findByEmail("mario.rossi@unicam.it");
        assertThat(found).isPresent();
        assertThat(found.get().getDisplayName()).isEqualTo("Mario");
        assertThat(found.get().getRoles()).containsExactly(LearnerAccount.ROLE_LEARNER);
    }

    @Test
    @DisplayName("refuses two accounts with the same address")
    void emailIsUnique() {
        account("mario@unicam.it");
        em.flush();

        account("mario@unicam.it");
        // The constraint is in the database, not only in the service. That is
        // what makes it hold under a race between two simultaneous
        // registrations, which an application-level check alone does not.
        assertThatThrownBy(() -> em.flush()).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("stores study days and computes a streak from them")
    void studyDaysRoundTrip() {
        LearnerAccount a = account("streaks@unicam.it");
        LocalDate monday = LocalDate.of(2026, 3, 2);
        a.recordStudyDay(monday);
        a.recordStudyDay(monday.plusDays(1));
        a.recordStudyDay(monday.plusDays(2));
        em.flush();
        em.clear();

        LearnerAccount reloaded = em.find(LearnerAccount.class, a.getId());
        assertThat(reloaded.getStudyDays()).hasSize(3);
        assertThat(reloaded.currentStreak(monday.plusDays(2))).isEqualTo(3);
        assertThat(reloaded.getBestStreak()).isEqualTo(3);
    }

    @Test
    @DisplayName("finds a session by token hash and brings its account with it")
    void sessionLookupFetchesTheAccount() {
        LearnerAccount a = account("session@unicam.it");
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        sessions.save(AuthSession.issue(a, "a".repeat(64), now, "JUnit"));
        em.flush();
        em.clear();

        Optional<AuthSession> found = sessions.findByTokenHash("a".repeat(64));
        assertThat(found).isPresent();
        // The named query uses JOIN FETCH, so this getter must not fire a
        // second SELECT. If the fetch were removed this line would still pass
        // inside an open transaction and fail in the filter, which is exactly
        // the bug that makes lazy loading confusing.
        assertThat(found.get().getAccount().getDisplayName()).isEqualTo("Mario");
        assertThat(found.get().isExpired(now)).isFalse();
        assertThat(found.get().isExpired(now.plus(AuthSession.LIFETIME))).isTrue();
    }

    @Test
    @DisplayName("deletes expired sessions and leaves live ones alone")
    void sweepDeletesOnlyExpired() {
        LearnerAccount a = account("sweep@unicam.it");
        Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        sessions.save(AuthSession.issue(a, "b".repeat(64), longAgo, "old"));
        sessions.save(AuthSession.issue(a, "c".repeat(64), now, "current"));
        em.flush();

        int removed = sessions.deleteExpired(now);
        assertThat(removed).isEqualTo(1);
        assertThat(sessions.findByTokenHash("c".repeat(64))).isPresent();
    }

    @Test
    @DisplayName("keeps one card per account and key")
    void cardKeyIsUniquePerAccount() {
        LearnerAccount a = account("cards@unicam.it");
        progress.add(CardProgress.start(a, "quiz:abc", "ch-persistence"));
        em.flush();

        progress.add(CardProgress.start(a, "quiz:abc", "ch-persistence"));
        assertThatThrownBy(() -> em.flush()).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("loads only the cards a sync asked for")
    void loadsCardsByKey() {
        LearnerAccount a = account("bykey@unicam.it");
        progress.add(CardProgress.start(a, "quiz:one", "ch-a"));
        progress.add(CardProgress.start(a, "quiz:two", "ch-a"));
        progress.add(CardProgress.start(a, "quiz:three", "ch-b"));
        em.flush();
        em.clear();

        LearnerAccount reloaded = em.find(LearnerAccount.class, a.getId());
        List<CardProgress> some = progress.cardsFor(reloaded, Arrays.asList("quiz:one", "quiz:three"));
        assertThat(some).hasSize(2);

        // An empty IN list is a syntax error on several databases, so the
        // repository short-circuits instead of building one.
        assertThat(progress.cardsFor(reloaded, java.util.Collections.emptyList())).isEmpty();
    }

    @Test
    @DisplayName("never returns another account's note")
    void notesAreScopedToTheirOwner() {
        LearnerAccount mine = account("mine@unicam.it");
        LearnerAccount theirs = account("theirs@unicam.it");
        StickyNote theirNote = notes.save(
                StickyNote.write(theirs, "ch-persistence", "their secret", "amber", 1));
        em.flush();
        em.clear();

        LearnerAccount me = em.find(LearnerAccount.class, mine.getId());
        // This is the insecure-direct-object-reference test. If findOwned ever
        // stops filtering by account, this is the line that goes red.
        assertThat(notes.findOwned(me, theirNote.getId())).isEmpty();
        assertThat(notes.findOwned(me, null)).isEmpty();
        assertThat(notes.findOwned(me, 987654321L)).isEmpty();
    }

    @Test
    @DisplayName("returns zero rather than null for the first note on an empty board")
    void highestSortIndexOnAnEmptyBoard() {
        LearnerAccount a = account("empty@unicam.it");
        em.flush();
        // MAX over no rows is NULL in SQL. Without the COALESCE this unboxes
        // into a NullPointerException on the very first note anybody writes -
        // a bug that no test with existing data would ever find.
        assertThat(notes.highestSortIndex(a)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("orders notes by their sort index")
    void notesAreOrdered() {
        LearnerAccount a = account("board@unicam.it");
        notes.save(StickyNote.write(a, "ch-a", "third", "amber", 3));
        notes.save(StickyNote.write(a, "ch-a", "first", "sage", 1));
        notes.save(StickyNote.write(a, "ch-b", "second", "sky", 2));
        em.flush();
        em.clear();

        LearnerAccount reloaded = em.find(LearnerAccount.class, a.getId());
        assertThat(notes.findAllFor(reloaded))
                .extracting(StickyNote::getBody)
                .containsExactly("first", "second", "third");
        assertThat(notes.findFor(reloaded, "ch-a"))
                .extracting(StickyNote::getBody)
                .containsExactly("first", "third");
    }

    @Test
    @DisplayName("wipes progress without touching the account or its notes")
    void resetRemovesProgressOnly() {
        LearnerAccount a = account("reset@unicam.it");
        progress.add(CardProgress.start(a, "quiz:x", "ch-a"));
        progress.add(ChapterProgress.start(a, "ch-a"));
        notes.save(StickyNote.write(a, "ch-a", "kept", "amber", 1));
        em.flush();

        int removed = progress.resetFor(a);
        assertThat(removed).isEqualTo(2);

        LearnerAccount reloaded = em.find(LearnerAccount.class, a.getId());
        assertThat(progress.cardsFor(reloaded)).isEmpty();
        assertThat(progress.chaptersFor(reloaded)).isEmpty();
        // Starting the course again should not throw your notebook away.
        assertThat(notes.findAllFor(reloaded)).hasSize(1);
    }

    @Test
    @DisplayName("stores the enum by name, so the value is readable in the table")
    void enumIsStoredAsAString() {
        LearnerAccount a = account("enum@unicam.it");
        CardProgress card = CardProgress.start(a, "quiz:enum", "ch-a");
        card.record(false, Instant.parse("2026-03-01T10:00:00Z"));
        progress.add(card);
        em.flush();

        Object stored = em.createNativeQuery(
                        "SELECT last_result FROM fieldbook_cards WHERE card_key = 'quiz:enum'")
                .getSingleResult();
        // EnumType.ORDINAL would store 1 here, and reordering the enum would
        // then silently change what every existing row means.
        assertThat(String.valueOf(stored)).isEqualTo("WRONG");
    }
}
