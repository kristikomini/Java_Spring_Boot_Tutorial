package it.unicam.cs.enrollment.mail.api;

import it.unicam.cs.enrollment.common.Page;
import it.unicam.cs.enrollment.exception.InvalidRequestException;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.fieldbook.security.Authenticated;
import it.unicam.cs.enrollment.fieldbook.security.CsrfProtected;
import it.unicam.cs.enrollment.fieldbook.security.CurrentUser;
import it.unicam.cs.enrollment.mail.MailConfig;
import it.unicam.cs.enrollment.mail.api.dto.MailboxStatusResponse;
import it.unicam.cs.enrollment.mail.api.dto.OutboxMessageResponse;
import it.unicam.cs.enrollment.mail.api.dto.SendTestMailRequest;
import it.unicam.cs.enrollment.mail.domain.MailStatus;
import it.unicam.cs.enrollment.mail.domain.OutboxMessage;
import it.unicam.cs.enrollment.mail.service.MailDispatcher;
import it.unicam.cs.enrollment.mail.service.MailService;
import it.unicam.cs.enrollment.mail.service.MailTemplates;
import it.unicam.cs.enrollment.mail.transport.MailTransport;
import it.unicam.cs.enrollment.web.dto.PageResponse;
import it.unicam.cs.enrollment.web.dto.PaginationParams;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The mailbox: what has been queued, what went out, what failed, and the two
 * buttons an operator needs when something is wrong.
 *
 * <h2>Why this is behind authentication and the rest of the API is not</h2>
 * The enrollment endpoints expose course catalogues and student records to
 * whoever can reach the server, which is a decision this learning application
 * makes for simplicity and would never make in production. This controller is
 * different in kind: every row it returns contains an email address and the
 * text of a message sent to a named person. An unauthenticated endpoint that
 * lists other people's correspondence is not a simplification, it is a data
 * breach with a URL.
 *
 * <p>So it reuses the fieldbook's session authentication - the only real auth
 * this application has - and requires the {@code author} role for anything that
 * causes an email to move. Reading is allowed to any signed-in account; sending
 * is not. That split (read for the many, write for the few) is the shape most
 * internal tools end up with.
 *
 * <h2>{@code @CsrfProtected} at class level</h2>
 * The session lives in a cookie, and a cookie is attached by the browser to any
 * request any page makes - including one triggered by a malicious site. The
 * CSRF interceptor demands a header that a cross-origin form cannot set. It
 * exempts GET, so the read endpoints are unaffected.
 */
@RestController
@RequestMapping(path = "/api/mail", produces = MediaType.APPLICATION_JSON_VALUE)
@Authenticated
@CsrfProtected
public class MailboxController {

    private final MailService mail;
    private final MailDispatcher dispatcher;
    private final MailTransport transport;
    private final MailConfig config;
    private final MailTemplates templates;
    private final CurrentUser currentUser;

    public MailboxController(MailService mail,
                             MailDispatcher dispatcher,
                             MailTransport transport,
                             MailConfig config,
                             MailTemplates templates,
                             CurrentUser currentUser) {
        this.mail = mail;
        this.dispatcher = dispatcher;
        this.transport = transport;
        this.config = config;
        this.templates = templates;
        this.currentUser = currentUser;
    }

    // ==================================================================
    // GET /api/mail/status
    // ==================================================================

    /**
     * Configuration and queue depth: the first thing to look at when mail is
     * not arriving.
     *
     * <p>Note that {@code transport.describe()} reports the transport that is
     * actually running, not the one that was requested. When {@code AUTO} falls
     * back to log-only because no mail host was configured, this is where that
     * shows up - and finding it here takes seconds, where finding it in a
     * startup log takes a support ticket.
     */
    @GetMapping("/status")
    public MailboxStatusResponse status() {
        MailboxStatusResponse dto = new MailboxStatusResponse();
        dto.setDeliveryEnabled(config.isEnabled());
        dto.setTransport(transport.describe());
        dto.setFromAddress(config.getFromAddress());
        dto.setRedirectTo(config.getRedirectTo().orElse(null));
        dto.setMaxAttempts(config.getRetryPolicy().getMaxAttempts());
        dto.setBatchSize(config.getBatchSize());
        dto.setRetentionDays(config.getRetentionDays());
        dto.setTemplates(templates.knownTemplates());

        // Walking MailStatus.values() rather than the map's own iteration order
        // fixes the order of the JSON keys to the order the enum declares
        // (PENDING, SENDING, SENT, DEAD, CANCELLED), which is the order a human
        // reads them in.
        Map<MailStatus, Long> counts = mail.countByStatus();
        Map<String, Long> asStrings = new LinkedHashMap<>();
        for (MailStatus status : MailStatus.values()) {
            asStrings.put(status.name(), counts.getOrDefault(status, 0L));
        }
        dto.setCounts(asStrings);

        return dto;
    }

    // ==================================================================
    // GET /api/mail/outbox
    // ==================================================================

    /** The queue, newest first, optionally filtered by status. */
    @GetMapping("/outbox")
    public PageResponse<OutboxMessageResponse> list(
            @RequestParam(name = "status", required = false) String status,
            PaginationParams pagination) {
        Page<OutboxMessage> page = mail.list(parseStatus(status), pagination.toPageRequest());
        return PageResponse.from(page, OutboxMessageResponse::summary);
    }

    // ==================================================================
    // GET /api/mail/outbox/{id}
    // ==================================================================

    /** One message, including the body that was rendered for it. */
    @GetMapping("/outbox/{id}")
    public OutboxMessageResponse get(@PathVariable("id") Long id) {
        return OutboxMessageResponse.full(mail.findById(id));
    }

    // ==================================================================
    // POST /api/mail/outbox/{id}/requeue
    // ==================================================================

    /**
     * Put a dead message back in the queue - after the address was corrected, or
     * the relay was fixed.
     *
     * <p>POST rather than PUT: this is not "replace the message with this
     * representation", it is "perform an action on it". A URL ending in a verb
     * is a compromise every real API makes somewhere, and it is a better one
     * than pretending a state machine transition is a resource update.
     */
    @PostMapping("/outbox/{id}/requeue")
    public OutboxMessageResponse requeue(@PathVariable("id") Long id) {
        requireAuthor();
        return OutboxMessageResponse.full(mail.requeue(id));
    }

    // ==================================================================
    // POST /api/mail/outbox/{id}/cancel
    // ==================================================================

    /** Stop a message that has not gone out yet. */
    @PostMapping("/outbox/{id}/cancel")
    public OutboxMessageResponse cancel(@PathVariable("id") Long id) {
        requireAuthor();
        return OutboxMessageResponse.full(mail.cancel(id));
    }

    // ==================================================================
    // POST /api/mail/dispatch
    // ==================================================================

    /**
     * Run a dispatch pass now instead of waiting up to thirty seconds for the
     * scheduler.
     *
     * <p>Worth the twelve lines: a background job that can only be observed by
     * waiting is a background job that is debugged by guessing. This one can be
     * triggered, watched, and its result read.
     */
    @PostMapping("/dispatch")
    public Map<String, Object> dispatchNow() {
        requireAuthor();
        int sent = dispatcher.dispatchOnce();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sent", sent);
        body.put("transport", transport.describe());
        return body;
    }

    // ==================================================================
    // POST /api/mail/test
    // ==================================================================

    /**
     * Queue one test message, to prove the whole path works end to end.
     *
     * <p>202 Accepted, not 200 OK, and the distinction is real: this endpoint
     * has not sent anything. It has written a row that the dispatcher will pick
     * up within half a minute. Returning 200 would claim more than happened -
     * and the difference is exactly what someone debugging a mail problem needs
     * to know.
     *
     * <p>See {@link SendTestMailRequest} for why the body is a fixed template
     * with one short note in it, rather than a subject and body of the caller's
     * choosing.
     */
    @PostMapping(path = "/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutboxMessageResponse> sendTest(@Valid @RequestBody SendTestMailRequest request) {
        requireAuthor();

        Map<String, String> model = new LinkedHashMap<>();
        model.put("sentBy", currentUser.require().getDisplayName());
        model.put("note", request.getNote() == null || request.getNote().trim().isEmpty()
                ? "(no note)" : request.getNote().trim());
        model.put("transport", transport.describe());

        OutboxMessage queued = mail.enqueueTemplate(
                MailTemplates.TEST_MESSAGE,
                request.getRecipient(),
                null,
                model,
                // No dedupe key: sending a second test after changing the
                // configuration is the entire point of a test message.
                null);

        return ResponseEntity.accepted().body(OutboxMessageResponse.full(queued));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Authorisation, as opposed to authentication.
     *
     * <p>The interceptor established WHO is calling; this decides whether they
     * may do this. Keeping the two separate is not pedantry - it is why a
     * signed-in learner can read the mailbox but cannot make the server send
     * anything.
     *
     * <p>{@link ResponseStatusException} is Spring's way of turning a status
     * code into an exception from anywhere in the stack. It is the direct
     * equivalent of JAX-RS's {@code ForbiddenException}, and in a project using
     * Spring Security this whole method would be one {@code @PreAuthorize} on
     * the handler instead.
     */
    private void requireAuthor() {
        if (!currentUser.hasRole(LearnerAccount.ROLE_AUTHOR)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This action needs the 'author' role.");
        }
    }

    private MailStatus parseStatus(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return MailStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw InvalidRequestException.invalidEnumValue("status", raw, MailStatus.class);
        }
    }
}
