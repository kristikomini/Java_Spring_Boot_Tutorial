package it.unicam.cs.enrollment.mail.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox state machine.
 *
 * <p>These tests need no database, no container and no mocks, because the rules
 * they check live in the entity rather than in a service. That is the argument
 * for putting behaviour on the domain object stated as a test suite: the same
 * rules implemented in {@code MailDispatcher} would require a mocked repository
 * and a mocked transport to reach.
 */
@DisplayName("OutboxMessage")
class OutboxMessageTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final RetryPolicy POLICY =
            new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMinutes(30));

    private static OutboxMessage queued() {
        return OutboxMessage.queue(
                MailMessage.to("mario.rossi@studenti.unicam.it")
                        .named("Mario Rossi")
                        .subject("You are enrolled in CS101")
                        .body("Dear Mario, ...")
                        .fromTemplate("enrollment-confirmed")
                        .dedupeKey("enrollment-confirmed:42")
                        .build(),
                NOW);
    }

    @Nested
    @DisplayName("when queued")
    class WhenQueued {

        @Test
        @DisplayName("starts PENDING, unattempted, and due immediately")
        void initialState() {
            OutboxMessage row = queued();

            assertThat(row.getStatus()).isEqualTo(MailStatus.PENDING);
            assertThat(row.getAttempts()).isZero();
            assertThat(row.getNextAttemptAt()).isEqualTo(NOW);
            assertThat(row.isDue(NOW)).isTrue();
            assertThat(row.getSentAt()).isNull();
        }

        @Test
        @DisplayName("normalises the recipient through the Email value object")
        void normalisesRecipient() {
            OutboxMessage row = OutboxMessage.queue(
                    MailMessage.to("  Mario.Rossi@Studenti.UNICAM.it ")
                            .subject("s").body("b").build(),
                    NOW);

            assertThat(row.getRecipient().getValue()).isEqualTo("mario.rossi@studenti.unicam.it");
        }

        @Test
        @DisplayName("cuts an over-long subject down to the column width")
        void truncatesSubject() {
            String tooLong = "x".repeat(400);
            OutboxMessage row = OutboxMessage.queue(
                    MailMessage.to("a@b.it").subject(tooLong).body("b").build(), NOW);

            // Truncating beats letting the database reject the row: a
            // confirmation with a clipped subject still arrives, while a
            // constraint violation would roll back the enrollment that caused it.
            assertThat(row.getSubject()).hasSize(OutboxMessage.MAX_SUBJECT);
            assertThat(row.getSubject()).endsWith("...");
        }
    }

    @Nested
    @DisplayName("the happy path")
    class HappyPath {

        @Test
        @DisplayName("claim then success: PENDING -> SENDING -> SENT")
        void claimAndSend() {
            OutboxMessage row = queued();

            row.markSending(NOW);
            assertThat(row.getStatus()).isEqualTo(MailStatus.SENDING);
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getClaimedAt()).isEqualTo(NOW);
            assertThat(row.isDue(NOW)).as("a claimed message is not claimable again").isFalse();

            Instant sentAt = NOW.plusSeconds(2);
            row.markSent(sentAt);
            assertThat(row.getStatus()).isEqualTo(MailStatus.SENT);
            assertThat(row.getSentAt()).isEqualTo(sentAt);
            assertThat(row.getClaimedAt()).isNull();
        }

        @Test
        @DisplayName("a claimed message can be turned into a value object safely")
        void snapshot() {
            OutboxMessage row = queued();
            MailMessage snapshot = row.toMailMessage();

            assertThat(snapshot.getRecipient().getValue()).isEqualTo("mario.rossi@studenti.unicam.it");
            assertThat(snapshot.getSubject()).isEqualTo("You are enrolled in CS101");
            assertThat(snapshot.getRecipientName()).contains("Mario Rossi");
            assertThat(snapshot.formattedRecipient())
                    .isEqualTo("Mario Rossi <mario.rossi@studenti.unicam.it>");
        }
    }

    @Nested
    @DisplayName("failure handling")
    class Failures {

        @Test
        @DisplayName("a transient failure goes back to PENDING, due after a backoff")
        void transientFailureRetries() {
            OutboxMessage row = queued();
            row.markSending(NOW);

            row.markFailed("Connection refused", false, POLICY, NOW);

            assertThat(row.getStatus()).isEqualTo(MailStatus.PENDING);
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getLastError()).isEqualTo("Connection refused");
            assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
            assertThat(row.isDue(NOW)).as("not due yet - that is the point of a backoff").isFalse();
            assertThat(row.isDue(NOW.plusSeconds(30))).isTrue();
        }

        @Test
        @DisplayName("a permanent failure goes straight to DEAD, budget untouched")
        void permanentFailureIsFinal() {
            OutboxMessage row = queued();
            row.markSending(NOW);

            row.markFailed("550 no such mailbox", true, POLICY, NOW);

            assertThat(row.getStatus()).isEqualTo(MailStatus.DEAD);
            assertThat(row.getAttempts()).as("one attempt, not three").isEqualTo(1);
        }

        @Test
        @DisplayName("dies once the retry budget is spent")
        void exhaustsTheBudget() {
            OutboxMessage row = queued();

            for (int attempt = 1; attempt <= 3; attempt++) {
                row.markSending(NOW);
                row.markFailed("Connection refused", false, POLICY, NOW);
            }

            assertThat(row.getAttempts()).isEqualTo(3);
            assertThat(row.getStatus()).isEqualTo(MailStatus.DEAD);
        }

        @Test
        @DisplayName("a long error message is cut to fit its column")
        void truncatesError() {
            OutboxMessage row = queued();
            row.markSending(NOW);

            row.markFailed("e".repeat(2000), false, POLICY, NOW);

            assertThat(row.getLastError()).hasSize(OutboxMessage.MAX_ERROR);
        }
    }

    @Nested
    @DisplayName("recovery")
    class Recovery {

        @Test
        @DisplayName("a claim older than the timeout counts as abandoned")
        void detectsStuckClaims() {
            OutboxMessage row = queued();
            row.markSending(NOW);

            assertThat(row.isStuck(NOW.plus(Duration.ofMinutes(5)), Duration.ofMinutes(10))).isFalse();
            assertThat(row.isStuck(NOW.plus(Duration.ofMinutes(11)), Duration.ofMinutes(10))).isTrue();
        }

        @Test
        @DisplayName("releasing an abandoned claim re-queues it but keeps the attempt")
        void releaseKeepsTheAttemptCount() {
            OutboxMessage row = queued();
            row.markSending(NOW);

            Instant later = NOW.plus(Duration.ofMinutes(11));
            row.releaseStuckClaim(later);

            assertThat(row.getStatus()).isEqualTo(MailStatus.PENDING);
            assertThat(row.isDue(later)).isTrue();
            // The attempt really happened and may even have delivered the mail.
            // Forgetting it would let a message that kills its dispatcher be
            // retried forever.
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getLastError()).contains("did not report an outcome");
        }

        @Test
        @DisplayName("a human can requeue a dead message with a fresh budget")
        void requeue() {
            OutboxMessage row = queued();
            row.markSending(NOW);
            row.markFailed("550 no such mailbox", true, POLICY, NOW);

            Instant later = NOW.plusSeconds(3600);
            row.requeue(later);

            assertThat(row.getStatus()).isEqualTo(MailStatus.PENDING);
            assertThat(row.getAttempts()).isZero();
            assertThat(row.isDue(later)).isTrue();
        }

        @Test
        @DisplayName("a pending message can be cancelled")
        void cancel() {
            OutboxMessage row = queued();

            row.cancel();

            assertThat(row.getStatus()).isEqualTo(MailStatus.CANCELLED);
            assertThat(row.isDue(NOW)).isFalse();
        }
    }

    @Nested
    @DisplayName("illegal transitions")
    class IllegalTransitions {

        /**
         * The value of these four assertions is not that anyone would write
         * those calls on purpose. It is that a future refactor of the
         * dispatcher - reordering two lines, retrying a step - fails here, in a
         * millisecond, instead of silently sending a message twice in
         * production.
         */
        @Test
        @DisplayName("are refused, loudly")
        void refused() {
            assertThatThrownBy(() -> queued().markSent(NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expected SENDING");

            assertThatThrownBy(() -> queued().markFailed("x", false, POLICY, NOW))
                    .isInstanceOf(IllegalStateException.class);

            assertThatThrownBy(() -> queued().requeue(NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dead or cancelled");

            OutboxMessage claimed = queued();
            claimed.markSending(NOW);
            assertThatThrownBy(() -> claimed.markSending(NOW))
                    .as("double-claiming is the bug this guards against")
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
