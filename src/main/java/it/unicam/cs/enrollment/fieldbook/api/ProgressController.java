package it.unicam.cs.enrollment.fieldbook.api;

import it.unicam.cs.enrollment.fieldbook.api.dto.CheckpointRequest;
import it.unicam.cs.enrollment.fieldbook.api.dto.SyncRequest;
import it.unicam.cs.enrollment.fieldbook.security.Authenticated;
import it.unicam.cs.enrollment.fieldbook.security.CsrfProtected;
import it.unicam.cs.enrollment.fieldbook.security.CurrentUser;
import it.unicam.cs.enrollment.fieldbook.service.ProgressService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The study record: read it, merge into it, reset it.
 *
 * <h2>Why the sync is a PUT and not a POST</h2>
 * Because sending the same body twice must leave the server in the same state
 * as sending it once - that is IDEMPOTENCE, and it is the property that makes a
 * flaky connection survivable. The browser can retry a failed sync without
 * having to know whether the first attempt got through, which matters because
 * over a dropped connection it genuinely cannot know.
 *
 * <p>The merge is written to make that true: taking a maximum and comparing
 * timestamps both give the same answer however many times you do them.
 * Endpoints described as idempotent that quietly increment something are worse
 * than endpoints that never claimed it.
 *
 * <p>The checkpoint below is a POST for the opposite reason: two attempts ARE
 * two attempts, and the counter is supposed to move.
 */
@RestController
@RequestMapping(path = "/api/fieldbook/progress",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Authenticated
@CsrfProtected
public class ProgressController {

    private final ProgressService progress;
    private final CurrentUser currentUser;

    public ProgressController(ProgressService progress, CurrentUser currentUser) {
        this.progress = progress;
        this.currentUser = currentUser;
    }

    /**
     * The current snapshot.
     *
     * <p>The chapter catalogue arrives as a repeated query parameter, because a
     * GET has no body. It is optional: with no catalogue the mastery percentage
     * comes back as zero rather than as an error, since a caller that only
     * wants the raw cards should not have to describe the whole course to get
     * them.
     */
    @GetMapping
    public ProgressService.Snapshot snapshot(
            @RequestParam(name = "chapter", required = false) List<String> catalogue,
            @RequestParam(name = "checkpoint", required = false) List<String> withCheckpoint) {
        return progress.snapshot(
                currentUser.require(),
                catalogue == null ? Collections.emptyList() : catalogue,
                withCheckpoint == null ? Collections.emptyList() : withCheckpoint);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProgressService.Snapshot sync(@Valid @RequestBody SyncRequest request) {
        return progress.sync(
                currentUser.require(),
                request.getCatalogue(),
                request.getWithCheckpoint(),
                request.getCards(),
                request.getChapters());
    }

    /**
     * Record one checkpoint attempt.
     *
     * <p>Answers 200 with a tiny body saying whether this was the first pass,
     * because that is the moment the page turns into a milestone and the client
     * should not have to diff two snapshots to notice it. Telling a caller what
     * changed is cheaper for everyone than making them work it out.
     */
    @PostMapping(path = "/checkpoint", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> checkpoint(@Valid @RequestBody CheckpointRequest request) {
        boolean firstPass = progress.recordCheckpoint(
                currentUser.require(), request.getChapterId(), request.getScore());
        return Collections.singletonMap("firstPass", firstPass);
    }

    @PostMapping("/read")
    public ResponseEntity<Void> markRead(@RequestParam(name = "chapter", required = false) String chapterId) {
        if (chapterId == null || chapterId.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        progress.markRead(currentUser.require(), chapterId.trim());
        return ResponseEntity.noContent().build();
    }

    /**
     * Start the course again.
     *
     * <p>A DELETE that wipes months of study deserves more than an accidental
     * click, so the client asks twice. Server side there is no undo and none is
     * pretended: the honest design is a confirmation, not a fake recycle bin.
     */
    @DeleteMapping
    public Map<String, Object> reset() {
        int wiped = progress.reset(currentUser.require());
        return Collections.singletonMap("removed", wiped);
    }
}
