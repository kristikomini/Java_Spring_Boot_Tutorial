package it.unicam.cs.enrollment.mail.service;

import it.unicam.cs.enrollment.mail.MailConfig;
import it.unicam.cs.enrollment.mail.domain.MailMessage;
import it.unicam.cs.enrollment.mail.domain.OutboxMessage;
import it.unicam.cs.enrollment.mail.repository.MailOutboxRepository;
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
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Queueing rules, with no database in sight.
 *
 * <p>Every collaborator is a mock, so what is under test is exactly the
 * decisions this class makes - dedupe, subject prefix, correlation id - and not
 * whether Hibernate can write a row. The repository integration test covers
 * that separately, which is the point of keeping the two kinds of test apart.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MailService (queueing)")
class MailServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @Mock
    private MailOutboxRepository outbox;

    @Mock
    private MailConfig config;

    @Mock
    private Logger log;

    private MailService service;

    @BeforeEach
    void setUp() {
        service = new MailService(outbox, new MailTemplates(), config,
                Clock.fixed(NOW, ZoneOffset.UTC), log);

        // lenient: the dedupe path returns before either of these is consulted.
        lenient().when(config.getSubjectPrefix()).thenReturn("");
        lenient().when(outbox.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static MailMessage message(String dedupeKey) {
        return MailMessage.to("mario@studenti.unicam.it")
                .named("Mario Rossi")
                .subject("You are enrolled in CS101")
                .body("Dear Mario, ...")
                .fromTemplate("enrollment-confirmed")
                .dedupeKey(dedupeKey)
                .build();
    }

    @Test
    @DisplayName("writes a PENDING row, due immediately")
    void queuesTheMessage() {
        when(outbox.findByDedupeKey("k")).thenReturn(Optional.empty());

        OutboxMessage queued = service.enqueue(message("k"));

        ArgumentCaptor<OutboxMessage> saved = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outbox).save(saved.capture());

        assertThat(saved.getValue().getRecipient().getValue()).isEqualTo("mario@studenti.unicam.it");
        assertThat(saved.getValue().getNextAttemptAt()).isEqualTo(NOW);
        assertThat(queued).isSameAs(saved.getValue());
    }

    @Test
    @DisplayName("does not queue the same dedupe key twice")
    void deduplicates() {
        OutboxMessage existing = OutboxMessage.queue(message("enrollment-confirmed:42"), NOW);
        when(outbox.findByDedupeKey("enrollment-confirmed:42")).thenReturn(Optional.of(existing));

        OutboxMessage result = service.enqueue(message("enrollment-confirmed:42"));

        // The whole point: a retried transaction must not produce a second
        // confirmation. The existing row comes back so the caller can still log
        // an id and carry on.
        assertThat(result).isSameAs(existing);
        verify(outbox, never()).save(any());
    }

    @Test
    @DisplayName("queues a message with no dedupe key without asking")
    void noDedupeKeyMeansNoLookup() {
        service.enqueue(message(null));

        verify(outbox, never()).findByDedupeKey(any());
        verify(outbox).save(any());
    }

    @Test
    @DisplayName("applies the subject prefix before the row is written")
    void appliesSubjectPrefix() {
        when(config.getSubjectPrefix()).thenReturn("[STAGING] ");
        when(outbox.findByDedupeKey(any())).thenReturn(Optional.empty());

        OutboxMessage queued = service.enqueue(message("k"));

        // Stored, not applied at send time, so the outbox shows exactly the
        // subject that will be delivered.
        assertThat(queued.getSubject()).isEqualTo("[STAGING] You are enrolled in CS101");
    }

    @Test
    @DisplayName("renders a template and queues the result in one step")
    void enqueueTemplate() {
        when(outbox.findByDedupeKey(any())).thenReturn(Optional.empty());

        Map<String, String> model = new LinkedHashMap<>();
        model.put("studentName", "Mario Rossi");
        model.put("studentNumber", "123456");
        model.put("courseCode", "CS101");
        model.put("courseTitle", "Programming Fundamentals");
        model.put("enrolledOn", "1 March 2026");

        OutboxMessage queued = service.enqueueTemplate(
                MailTemplates.ENROLLMENT_CONFIRMED,
                "mario@studenti.unicam.it", "Mario Rossi", model, "enrollment-confirmed:42");

        assertThat(queued.getSubject()).isEqualTo("You are enrolled in CS101");
        assertThat(queued.getBody()).contains("Programming Fundamentals");
        assertThat(queued.getTemplateKey()).isEqualTo(MailTemplates.ENROLLMENT_CONFIRMED);
        assertThat(queued.getDedupeKey()).isEqualTo("enrollment-confirmed:42");
    }

    @Test
    @DisplayName("purges delivered mail older than the retention window")
    void purgeUsesTheConfiguredWindow() {
        when(config.getRetentionDays()).thenReturn(30);
        when(outbox.purgeSentBefore(any())).thenReturn(7);

        int deleted = service.purgeOldMessages();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(outbox).purgeSentBefore(cutoff.capture());

        assertThat(deleted).isEqualTo(7);
        assertThat(cutoff.getValue()).isEqualTo(NOW.minusSeconds(30L * 86_400));
    }
}
