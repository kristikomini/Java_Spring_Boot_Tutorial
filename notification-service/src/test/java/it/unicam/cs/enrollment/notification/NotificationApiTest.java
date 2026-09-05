package it.unicam.cs.enrollment.notification;

import it.unicam.cs.enrollment.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract of the notification service, which is almost entirely about
 * being called more than once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("The notification service contract")
class NotificationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationService notificationService;

    private static String event(String eventId) {
        return """
                {"eventId":"%s","enrollmentId":1,"studentNumber":"S1234567",
                 "studentEmail":"a@b.it","courseCode":"CS100",
                 "courseTitle":"Intro","occurredAt":"2026-09-05T10:00:00Z"}
                """.formatted(eventId);
    }

    @Test
    @DisplayName("a new event is accepted with 202 and processed")
    void acceptsNewEvent() throws Exception {
        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-new-1")))
                // 202, not 200 or 201: responsibility taken, work not necessarily
                // finished. The honest status for asynchronous work, and the
                // whole justification for this boundary was that nobody waits.
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.processed").value(true));
    }

    @Test
    @DisplayName("a redelivered event is 202 AND NOT processed twice")
    void deduplicatesRedelivery() throws Exception {
        String body = event("evt-dupe-1");

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.processed").value(true));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/notifications")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    // STILL 202, and this is the subtle part. Returning 409 for a
                    // duplicate would be wrong: from the caller point of view the
                    // event HAS been accepted - it was accepted the first time -
                    // and a retry that receives an error would be retried again
                    // forever. An idempotent endpoint reports success for a
                    // repeat and says in the body that nothing new happened.
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.processed").value(false));
        }
    }

    @Test
    @DisplayName("concurrent redelivery still processes exactly once")
    void deduplicationIsThreadSafe() throws Exception {
        String body = event("evt-race-1");
        int threads = 12;

        long acceptedBefore = notificationService.acceptedCount();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    gate.await();
                    mockMvc.perform(post("/api/notifications")
                            .contentType(MediaType.APPLICATION_JSON).content(body));
                } catch (Exception ignored) {
                    // The counter below is the assertion.
                } finally {
                    done.countDown();
                }
            });
        }

        // All twelve released at once. Without the start gate the first request
        // finishes before the last is scheduled and nothing actually races.
        gate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // EXACTLY ONE. This is what putIfAbsent buys: a check-then-act written
        // as "if not containsKey then put" would let two threads through and
        // send two emails, and it would pass every single-threaded test.
        assertThat(notificationService.acceptedCount() - acceptedBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("an event missing its idempotency key is rejected")
    void rejectsEventWithoutEventId() throws Exception {
        // Without an eventId the receiver has no way to recognise a retry, so
        // accepting one would silently disable deduplication for that caller.
        // Better to refuse it loudly than to send duplicate emails quietly.
        String noKey = event("x").replace("\"eventId\":\"x\"", "\"eventId\":\"\"");

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noKey))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the caller correlation id is ADOPTED, not replaced")
    void adoptsCallerCorrelationId() throws Exception {
        // The half that makes two logs joinable. If this service generated its
        // own id instead, both logs would be individually tidy and impossible to
        // correlate - which is the state most two-service systems are in.
        mockMvc.perform(post("/api/notifications")
                        .header("X-Correlation-Id", "CALLERID1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-corr-1")))
                .andExpect(header().string("X-Correlation-Id", "CALLERID1"));
    }

    @Test
    @DisplayName("a hostile correlation id is sanitised here too")
    void sanitisesCorrelationId() throws Exception {
        // Applied HERE as well as at the caller, because this service cannot
        // know that whoever called it already sanitised the value. Log injection
        // is a per-boundary concern, not a per-system one.
        mockMvc.perform(post("/api/notifications")
                        .header("X-Correlation-Id", "abc\ndef INFO forged line")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-corr-2")))
                .andExpect(header().string("X-Correlation-Id",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\n"))));
    }

    @Test
    @DisplayName("health is exposed so a caller can tell whether this service is up")
    void healthIsExposed() throws Exception {
        // A requirement that did not exist when this was a CDI observer in the
        // same JVM: a method call cannot be "down".
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
