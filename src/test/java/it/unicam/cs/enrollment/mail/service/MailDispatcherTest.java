package it.unicam.cs.enrollment.mail.service;

import it.unicam.cs.enrollment.mail.MailConfig;
import it.unicam.cs.enrollment.mail.domain.MailMessage;
import it.unicam.cs.enrollment.mail.transport.MailDeliveryException;
import it.unicam.cs.enrollment.mail.transport.MailTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dispatch loop: what it sends, what it records, and - the interesting part -
 * what it does when things go wrong.
 *
 * <p>The transport is a mock, so "the SMTP server refused the recipient" is one
 * line of setup rather than a mail server in a container. That is the return on
 * having defined {@code MailTransport} as an interface: failure modes that are
 * hard to produce for real become trivial to produce for a test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MailDispatcher")
class MailDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @Mock
    private OutboxProcessor processor;

    @Mock
    private MailTransport transport;

    @Mock
    private MailConfig config;

    private MailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new MailDispatcher();
        // Package-private field injection - see the comment on those fields for
        // why an EJB cannot take its dependencies through a constructor.
        dispatcher.processor = processor;
        dispatcher.transport = transport;
        dispatcher.config = config;
        dispatcher.clock = Clock.fixed(NOW, ZoneOffset.UTC);

        lenient().when(config.getBatchSize()).thenReturn(25);
        lenient().when(config.isEnabled()).thenReturn(true);
        lenient().when(transport.describe()).thenReturn("test transport");
    }

    private static MailMessage message() {
        return MailMessage.to("mario@studenti.unicam.it")
                .subject("You are enrolled in CS101")
                .body("Dear Mario, ...")
                .build();
    }

    @Test
    @DisplayName("claims, sends and records each due message")
    void happyPath() throws Exception {
        when(processor.findDue(NOW, 25)).thenReturn(Arrays.asList(1L, 2L));
        when(processor.claim(any(), eq(NOW))).thenReturn(Optional.of(message()));

        int sent = dispatcher.dispatchOnce();

        assertThat(sent).isEqualTo(2);
        verify(transport, org.mockito.Mockito.times(2)).send(any());
        verify(processor).recordSuccess(1L, NOW);
        verify(processor).recordSuccess(2L, NOW);
    }

    @Test
    @DisplayName("does nothing when the queue is empty")
    void emptyQueue() throws Exception {
        when(processor.findDue(NOW, 25)).thenReturn(Collections.emptyList());

        assertThat(dispatcher.dispatchOnce()).isZero();
        verify(transport, never()).send(any());
    }

    @Test
    @DisplayName("skips a message someone else already claimed")
    void claimLost() throws Exception {
        when(processor.findDue(NOW, 25)).thenReturn(Collections.singletonList(1L));
        when(processor.claim(1L, NOW)).thenReturn(Optional.empty());

        assertThat(dispatcher.dispatchOnce()).isZero();

        // Not sent, and specifically not recorded as a failure: losing a race is
        // a normal outcome, and marking it failed would burn a retry from the
        // budget of a message another dispatcher is delivering right now.
        verify(transport, never()).send(any());
        verify(processor, never()).recordFailure(any(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("records a transient failure as retryable")
    void transientFailure() throws Exception {
        when(processor.findDue(NOW, 25)).thenReturn(Collections.singletonList(1L));
        when(processor.claim(1L, NOW)).thenReturn(Optional.of(message()));
        doThrow(MailDeliveryException.transientFailure("Connection refused", null))
                .when(transport).send(any());

        assertThat(dispatcher.dispatchOnce()).isZero();

        verify(processor).recordFailure(1L, "Connection refused", false, NOW);
    }

    @Test
    @DisplayName("records a permanent failure as permanent")
    void permanentFailure() throws Exception {
        when(processor.findDue(NOW, 25)).thenReturn(Collections.singletonList(1L));
        when(processor.claim(1L, NOW)).thenReturn(Optional.of(message()));
        doThrow(MailDeliveryException.permanent("550 no such mailbox", null))
                .when(transport).send(any());

        dispatcher.dispatchOnce();

        verify(processor).recordFailure(1L, "550 no such mailbox", true, NOW);
    }

    @Test
    @DisplayName("one broken message does not stop the rest of the batch")
    void aBadMessageDoesNotBlockTheQueue() throws Exception {
        when(processor.findDue(NOW, 25)).thenReturn(Arrays.asList(1L, 2L));
        when(processor.claim(any(), eq(NOW))).thenReturn(Optional.of(message()));
        doThrow(new IllegalStateException("transport bug"))
                .doNothing()
                .when(transport).send(any());

        int sent = dispatcher.dispatchOnce();

        // An undeclared RuntimeException from a transport is a bug in the
        // transport, not a delivery outcome - but letting it propagate would
        // abort the batch and block every message behind the broken one. A queue
        // must never be stoppable by a single bad entry.
        assertThat(sent).isEqualTo(1);
        verify(processor).recordFailure(eq(1L), anyString(), eq(false), eq(NOW));
        verify(processor).recordSuccess(2L, NOW);
    }

    @Test
    @DisplayName("sends nothing while delivery is switched off, but leaves the queue intact")
    void disabledKeepsTheBacklog() throws Exception {
        when(config.isEnabled()).thenReturn(false);
        when(processor.findDue(any(), anyInt())).thenReturn(Arrays.asList(1L, 2L));

        dispatcher.dispatchDue();

        verify(transport, never()).send(any());
        verify(processor, never()).claim(any(), any());
    }

    @Test
    @DisplayName("re-queues messages abandoned mid-send")
    void recoversStuckMessages() {
        when(processor.findStuck(NOW, 25)).thenReturn(Arrays.asList(7L, 8L));
        when(processor.release(any(), eq(NOW))).thenReturn(true);

        dispatcher.recoverStuckMessages();

        verify(processor).release(7L, NOW);
        verify(processor).release(8L, NOW);
    }
}
