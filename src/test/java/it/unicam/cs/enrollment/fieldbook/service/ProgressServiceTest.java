package it.unicam.cs.enrollment.fieldbook.service;

import it.unicam.cs.enrollment.domain.model.Email;
import it.unicam.cs.enrollment.fieldbook.domain.CardProgress;
import it.unicam.cs.enrollment.fieldbook.domain.ChapterProgress;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.fieldbook.repository.LearnerAccountRepository;
import it.unicam.cs.enrollment.fieldbook.repository.ProgressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The merge, which is the only genuinely hard piece of logic on the server.
 *
 * <p>These tests exist because "last write wins" is easy to say and easy to get
 * subtly wrong, and because the failure mode is a learner quietly losing a
 * week of study with nothing in the log.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProgressService (the offline merge)")
class ProgressServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant EARLIER = NOW.minusSeconds(3600);
    private static final Instant LATER = NOW.plusSeconds(3600);

    @Mock
    private ProgressRepository repository;

    @Mock
    private LearnerAccountRepository accountRepository;

    @Mock
    private AccountService accounts;

    @Mock
    private Logger log;

    private ProgressService service;
    private LearnerAccount account;

    @BeforeEach
    void setUp() {
        service = new ProgressService(repository, accountRepository, new MasteryCalculator(),
                accounts, Clock.fixed(NOW, ZoneOffset.UTC), log);
        account = LearnerAccount.register(Email.of("m@u.it"), "M", "hash", "Europe/Rome");
    }

    /**
     * Every write path now opens with {@code SELECT ... FOR UPDATE} on the
     * learner's own account row, so that a sync cannot interleave with another
     * sync from the same person. The tests stub that lookup to hand back the
     * same account instance they are asserting on.
     */
    private void expectLock() {
        // lenient: a test that syncs nothing never asks what day it is, and
        // strict stubbing would fail it for a stub it did not need.
        lenient().when(accountRepository.findById(any())).thenReturn(Optional.of(account));
        lenient().when(accounts.todayFor(any())).thenReturn(LocalDate.of(2026, 3, 1));
    }

    private ProgressService.CardState incoming(String key, int box, Instant updatedAt) {
        ProgressService.CardState s = new ProgressService.CardState();
        s.key = key;
        s.chapterId = "ch-persistence";
        s.box = box;
        s.seen = 3;
        s.last = "right";
        s.dueAt = LATER;
        s.updatedAt = updatedAt;
        return s;
    }

    /**
     * A server-side card whose merge clock the test controls.
     *
     * <p>This is only possible because the merge clock is a field of the entity
     * rather than the JPA audit column, which no test can set without a
     * database. Testability is not the reason that field exists, but needing a
     * database to test one comparison would have been a strong hint that
     * something was in the wrong place.
     */
    private CardProgress serverCard(String key, int box) {
        CardProgress c = CardProgress.start(account, key, "ch-persistence");
        c.restore(box, 1, CardProgress.Result.RIGHT, EARLIER, EARLIER);
        return c;
    }

    @Test
    @DisplayName("creates a card the server has never seen")
    void unknownCardIsCreated() {
        when(repository.cardsFor(any(), anyCollection())).thenReturn(Collections.emptyList());
        when(repository.cardsFor(any())).thenReturn(Collections.emptyList());
        when(repository.chaptersFor(any())).thenReturn(Collections.emptyList());
        expectLock();

        service.sync(account, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(incoming("quiz:new", 3, NOW)),
                Collections.emptyList());

        ArgumentCaptor<CardProgress> saved = ArgumentCaptor.forClass(CardProgress.class);
        verify(repository).add(saved.capture());
        assertThat(saved.getValue().getCardKey()).isEqualTo("quiz:new");
        assertThat(saved.getValue().getBox()).isEqualTo(3);
    }

    @Test
    @DisplayName("a newer client answer overwrites the server copy")
    void clientWinsWhenNewer() {
        CardProgress server = serverCard("quiz:a", 2);
        when(repository.cardsFor(any(), anyCollection())).thenReturn(Collections.singletonList(server));
        when(repository.cardsFor(any())).thenReturn(Collections.singletonList(server));
        when(repository.chaptersFor(any())).thenReturn(Collections.emptyList());
        expectLock();

        service.sync(account, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(incoming("quiz:a", 5, LATER)),
                Collections.emptyList());

        assertThat(server.getBox()).isEqualTo(5);
        verify(repository, never()).add(any(CardProgress.class));
    }

    @Test
    @DisplayName("an older client answer is discarded")
    void serverWinsWhenNewer() {
        CardProgress server = serverCard("quiz:a", 4);
        when(repository.cardsFor(any(), anyCollection())).thenReturn(Collections.singletonList(server));
        when(repository.cardsFor(any())).thenReturn(Collections.singletonList(server));
        when(repository.chaptersFor(any())).thenReturn(Collections.emptyList());
        expectLock();

        service.sync(account, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(incoming("quiz:a", 1, EARLIER.minusSeconds(600))),
                Collections.emptyList());

        assertThat(server.getBox()).isEqualTo(4);
    }

    @Test
    @DisplayName("a client that sends no timestamp cannot lower a box")
    void missingClientClockCannotLoseProgress() {
        CardProgress server = serverCard("quiz:a", 4);
        when(repository.cardsFor(any(), anyCollection())).thenReturn(Collections.singletonList(server));
        when(repository.cardsFor(any())).thenReturn(Collections.singletonList(server));
        when(repository.chaptersFor(any())).thenReturn(Collections.emptyList());
        expectLock();

        service.sync(account, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(incoming("quiz:a", 1, null)),
                Collections.emptyList());

        assertThat(server.getBox()).isEqualTo(4);
    }

    @Test
    @DisplayName("an exact timestamp tie resolves towards keeping progress")
    void tieGoesToTheHigherBox() {
        CardProgress server = serverCard("quiz:a", 2);
        when(repository.cardsFor(any(), anyCollection())).thenReturn(Collections.singletonList(server));
        when(repository.cardsFor(any())).thenReturn(Collections.singletonList(server));
        when(repository.chaptersFor(any())).thenReturn(Collections.emptyList());

        // Two devices syncing in the same second is exactly when this matters,
        // and losing the higher box would throw away work.
        expectLock();

        service.sync(account, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(incoming("quiz:a", 4, EARLIER)),
                Collections.emptyList());

        assertThat(server.getBox()).isEqualTo(4);
    }

    @Test
    @DisplayName("chapters merge field by field, taking the better of each")
    void chaptersTakeTheMaximum() {
        ChapterProgress server = ChapterProgress.start(account, "ch-persistence");
        server.restore(NOW, 90, 2, NOW);
        when(repository.cardsFor(any())).thenReturn(Collections.emptyList());
        when(repository.chaptersFor(any())).thenReturn(Collections.singletonList(server));
        expectLock();

        ProgressService.ChapterState incoming = new ProgressService.ChapterState();
        incoming.chapterId = "ch-persistence";
        incoming.readAt = EARLIER;
        incoming.bestScore = 40;
        incoming.attempts = 5;
        incoming.updatedAt = LATER;

        service.sync(account, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.singletonList(incoming));

        // The best score is a maximum by definition, so a later-but-worse
        // attempt must not lower it. Read time keeps the earliest, because you
        // cannot un-read a chapter.
        assertThat(server.getBestScore()).isEqualTo(90);
        assertThat(server.getAttempts()).isEqualTo(5);
        assertThat(server.getReadAt()).isEqualTo(EARLIER);
    }

    @Test
    @DisplayName("a sync that mentions nothing deletes nothing")
    void syncNeverDeletes() {
        CardProgress server = serverCard("quiz:kept", 3);
        when(repository.cardsFor(any())).thenReturn(Collections.singletonList(server));
        when(repository.chaptersFor(any())).thenReturn(Collections.emptyList());
        expectLock();

        ProgressService.Snapshot out = service.sync(account,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        // "Not mentioned" and "deleted" are indistinguishable over a lossy
        // connection, so the merge never guesses.
        assertThat(out.cards).hasSize(1);
        assertThat(out.cards.get(0).key).isEqualTo("quiz:kept");
    }

    @Test
    @DisplayName("records a study day only when something was actually answered")
    void studyDayOnlyOnActivity() {
        when(repository.cardsFor(any())).thenReturn(Collections.emptyList());
        when(repository.chaptersFor(any())).thenReturn(Collections.emptyList());
        when(accountRepository.findById(any())).thenReturn(Optional.of(account));

        service.sync(account, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        // An empty sync is a heartbeat, not study. Counting it would give a
        // streak to a browser that was merely left open.
        assertThat(account.getStudyDays()).isEmpty();
        verify(accounts, never()).todayFor(any());
    }

    @Test
    @DisplayName("records a study day when something was answered")
    void studyDayOnActivity() {
        when(repository.cardsFor(any(), anyCollection())).thenReturn(Collections.emptyList());
        when(repository.cardsFor(any())).thenReturn(Collections.emptyList());
        when(repository.chaptersFor(any())).thenReturn(Collections.emptyList());
        expectLock();

        service.sync(account, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(incoming("quiz:new", 2, NOW)),
                Collections.emptyList());

        assertThat(account.getStudyDays()).containsExactly(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("locks the account row before writing anything")
    void takesTheLock() {
        when(repository.cardsFor(any())).thenReturn(Collections.emptyList());
        when(repository.chaptersFor(any())).thenReturn(Collections.emptyList());
        when(accountRepository.findById(any())).thenReturn(Optional.of(account));

        service.sync(account, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        // Without this, two syncs from the same learner race on three separate
        // unique constraints and one of them becomes a 500. The lock is taken
        // as its own statement, before the entity is read - see
        // LearnerAccountRepository.lockRow for why that ordering matters.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(accountRepository);
        order.verify(accountRepository).lockRow(any());
        order.verify(accountRepository).findById(any());
    }

    @Test
    @DisplayName("reports mastery for the catalogue the client supplied")
    void snapshotComputesMastery() {
        ChapterProgress read = ChapterProgress.start(account, "ch-a");
        read.restore(NOW, 100, 1, NOW);
        when(repository.cardsFor(any())).thenReturn(Collections.emptyList());
        when(repository.chaptersFor(any())).thenReturn(Collections.singletonList(read));

        List<String> catalogue = new ArrayList<>(Arrays.asList("ch-a", "ch-b"));
        ProgressService.Snapshot snap =
                service.snapshot(account, catalogue, Arrays.asList("ch-a", "ch-b"));

        assertThat(snap.mastery.getChaptersTotal()).isEqualTo(2);
        assertThat(snap.mastery.getPercent()).isEqualTo(50);
    }
}
