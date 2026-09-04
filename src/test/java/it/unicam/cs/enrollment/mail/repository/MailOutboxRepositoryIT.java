package it.unicam.cs.enrollment.mail.repository;

import it.unicam.cs.enrollment.common.Page;
import it.unicam.cs.enrollment.common.PageRequest;
import it.unicam.cs.enrollment.mail.domain.MailMessage;
import it.unicam.cs.enrollment.mail.domain.MailStatus;
import it.unicam.cs.enrollment.mail.domain.OutboxMessage;
import it.unicam.cs.enrollment.mail.domain.RetryPolicy;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox queries, against a real (in-memory) database.
 *
 * <p>An {@code *IT} rather than a {@code *Test}: it runs in {@code mvn verify},
 * not in {@code mvn test}, because it costs seconds rather than milliseconds.
 * The split is what keeps the fast suite fast enough to run on every save.
 *
 * <p>What only a database can tell you is here: that the JPQL parses at all,
 * that the dedupe UNIQUE constraint is really enforced, that the bulk delete
 * removes what it should and nothing else. None of those can be checked with a
 * mocked repository, and all three would be found by a user otherwise.
 */
@DisplayName("MailOutboxRepository (H2)")
class MailOutboxRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final RetryPolicy POLICY =
            new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMinutes(30));

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private MailOutboxRepository repository;

    @BeforeAll
    static void createFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("enrollmentTestPU");
    }

    @AfterAll
    static void closeFactory() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    @BeforeEach
    void setUp() {
        entityManager = entityManagerFactory.createEntityManager();
        repository = new MailOutboxRepository();
        repository.useEntityManager(entityManager);
        entityManager.getTransaction().begin();
    }

    @AfterEach
    void tearDown() {
        // Roll back rather than delete: the next test starts from the schema as
        // it was created, and no test can leave debris for another one to trip
        // over. Test isolation by transaction is the cheapest kind there is.
        if (entityManager != null) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            entityManager.close();
        }
    }

    private OutboxMessage queue(String recipient, String dedupeKey, Instant dueAt) {
        OutboxMessage row = OutboxMessage.queue(
                MailMessage.to(recipient)
                        .subject("You are enrolled in CS101")
                        .body("Dear student, ...")
                        .fromTemplate("enrollment-confirmed")
                        .dedupeKey(dedupeKey)
                        .build(),
                dueAt);
        return repository.save(row);
    }

    @Test
    @DisplayName("finds only messages that are pending and due")
    void findDueIds() {
        OutboxMessage dueNow = queue("a@unicam.it", "k1", NOW.minusSeconds(60));
        queue("b@unicam.it", "k2", NOW.plusSeconds(600));          // not due yet
        OutboxMessage sent = queue("c@unicam.it", "k3", NOW.minusSeconds(60));
        sent.markSending(NOW);
        sent.markSent(NOW);
        repository.flush();

        List<Long> due = repository.findDueIds(NOW, 10);

        assertThat(due).containsExactly(dueNow.getId());
    }

    @Test
    @DisplayName("returns due messages oldest first, capped at the batch size")
    void findDueIdsIsOrderedAndLimited() {
        OutboxMessage oldest = queue("a@unicam.it", "k1", NOW.minusSeconds(300));
        OutboxMessage middle = queue("b@unicam.it", "k2", NOW.minusSeconds(200));
        queue("c@unicam.it", "k3", NOW.minusSeconds(100));
        repository.flush();

        assertThat(repository.findDueIds(NOW, 2))
                .containsExactly(oldest.getId(), middle.getId());
    }

    @Test
    @DisplayName("finds messages abandoned in SENDING")
    void findStuckIds() {
        OutboxMessage stuck = queue("a@unicam.it", "k1", NOW);
        stuck.markSending(NOW.minus(Duration.ofMinutes(30)));
        OutboxMessage fresh = queue("b@unicam.it", "k2", NOW);
        fresh.markSending(NOW);
        repository.flush();

        List<Long> ids = repository.findStuckIds(NOW.minus(Duration.ofMinutes(10)), 10);

        assertThat(ids).containsExactly(stuck.getId());
    }

    @Test
    @DisplayName("the database, not the application, is what stops a duplicate")
    void dedupeKeyIsUnique() {
        queue("a@unicam.it", "enrollment-confirmed:42", NOW);
        repository.flush();

        queue("b@unicam.it", "enrollment-confirmed:42", NOW);

        // The check in MailService is a courtesy that produces a friendly answer
        // in the common case. THIS is the guarantee: two concurrent callers can
        // both find nothing and both insert, and only one of them commits.
        assertThatThrownBy(() -> repository.flush())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("allows any number of messages with no dedupe key")
    void nullDedupeKeysDoNotCollide() {
        queue("a@unicam.it", null, NOW);
        queue("b@unicam.it", null, NOW);
        queue("c@unicam.it", null, NOW);

        // NULL is exempt from UNIQUE in SQL, which is exactly why the column is
        // nullable: messages with no natural "once" key must not be forced to
        // invent one.
        repository.flush();

        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("counts every status, including the empty ones")
    void countByStatus() {
        queue("a@unicam.it", "k1", NOW);
        OutboxMessage dead = queue("b@unicam.it", "k2", NOW);
        dead.markSending(NOW);
        dead.markFailed("550 no such mailbox", true, POLICY, NOW);
        repository.flush();

        Map<MailStatus, Long> counts = repository.countByStatus();

        assertThat(counts).containsEntry(MailStatus.PENDING, 1L)
                .containsEntry(MailStatus.DEAD, 1L)
                .containsEntry(MailStatus.SENT, 0L)
                .containsEntry(MailStatus.CANCELLED, 0L);
    }

    @Test
    @DisplayName("lists newest first, filtered by status")
    void findByStatus() {
        queue("a@unicam.it", "k1", NOW);
        OutboxMessage second = queue("b@unicam.it", "k2", NOW);
        repository.flush();

        Page<OutboxMessage> page = repository.findByStatus(MailStatus.PENDING, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getId()).isEqualTo(second.getId());
        assertThat(repository.findByStatus(MailStatus.SENT, PageRequest.of(0, 10)).getContent())
                .isEmpty();
    }

    @Test
    @DisplayName("finds a message by its dedupe key")
    void findByDedupeKey() {
        queue("a@unicam.it", "enrollment-confirmed:42", NOW);
        repository.flush();

        assertThat(repository.findByDedupeKey("enrollment-confirmed:42")).isPresent();
        assertThat(repository.findByDedupeKey("enrollment-confirmed:43")).isEmpty();
        assertThat(repository.findByDedupeKey(null)).isEmpty();
    }

    @Test
    @DisplayName("purges old delivered mail, and only that")
    void purgeSentBefore() {
        OutboxMessage old = queue("a@unicam.it", "k1", NOW);
        old.markSending(NOW.minus(Duration.ofDays(60)));
        old.markSent(NOW.minus(Duration.ofDays(60)));

        OutboxMessage recent = queue("b@unicam.it", "k2", NOW);
        recent.markSending(NOW);
        recent.markSent(NOW);

        OutboxMessage dead = queue("c@unicam.it", "k3", NOW);
        dead.markSending(NOW.minus(Duration.ofDays(60)));
        dead.markFailed("550 no such mailbox", true, POLICY, NOW.minus(Duration.ofDays(60)));
        repository.flush();

        int deleted = repository.purgeSentBefore(NOW.minus(Duration.ofDays(30)));

        assertThat(deleted).isEqualTo(1);
        // The dead letter survives on purpose: it is the record of mail somebody
        // was promised and never received, and a retention policy that deletes
        // the evidence of failure guarantees nobody ever fixes the cause.
        assertThat(repository.findById(dead.getId())).isPresent();
        assertThat(repository.findById(recent.getId())).isPresent();
    }

    @Test
    @DisplayName("round-trips every column, including the long body")
    void persistsTheWholeRow() {
        OutboxMessage row = queue("mario.rossi@studenti.unicam.it", "k1", NOW);
        row.setCorrelationId("abc-123");
        repository.flush();
        entityManager.clear();

        OutboxMessage reloaded = repository.findById(row.getId()).orElseThrow();

        assertThat(reloaded.getRecipient().getValue()).isEqualTo("mario.rossi@studenti.unicam.it");
        assertThat(reloaded.getSubject()).isEqualTo("You are enrolled in CS101");
        assertThat(reloaded.getBody()).isEqualTo("Dear student, ...");
        assertThat(reloaded.getTemplateKey()).isEqualTo("enrollment-confirmed");
        assertThat(reloaded.getStatus()).isEqualTo(MailStatus.PENDING);
        assertThat(reloaded.getCorrelationId()).isEqualTo("abc-123");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }
}
