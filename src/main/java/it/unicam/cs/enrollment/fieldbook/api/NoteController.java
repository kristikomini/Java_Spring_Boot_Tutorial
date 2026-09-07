package it.unicam.cs.enrollment.fieldbook.api;

import it.unicam.cs.enrollment.fieldbook.api.dto.MoveNoteRequest;
import it.unicam.cs.enrollment.fieldbook.api.dto.NoteRequest;
import it.unicam.cs.enrollment.fieldbook.api.dto.NoteResponse;
import it.unicam.cs.enrollment.fieldbook.domain.StickyNote;
import it.unicam.cs.enrollment.fieldbook.security.Authenticated;
import it.unicam.cs.enrollment.fieldbook.security.CsrfProtected;
import it.unicam.cs.enrollment.fieldbook.security.CurrentUser;
import it.unicam.cs.enrollment.fieldbook.service.NoteService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Sticky notes.
 *
 * <p>A conventional REST resource, and worth reading precisely because it is
 * conventional: collection at {@code /notes}, item at {@code /notes/{id}},
 * PATCH for a partial update, 201 with a {@code Location} header on create, 204
 * on delete. Following the convention is not pedantry - it means anybody who
 * has used an HTTP API before can predict this one without reading it.
 */
@RestController
@RequestMapping(path = "/api/fieldbook/notes",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Authenticated
@CsrfProtected
public class NoteController {

    private final NoteService notes;
    private final CurrentUser currentUser;

    public NoteController(NoteService notes, CurrentUser currentUser) {
        this.notes = notes;
        this.currentUser = currentUser;
    }

    /** All of them, or just one chapter. */
    @GetMapping
    public List<NoteResponse> list(@RequestParam(name = "chapter", required = false) String chapterId) {
        List<StickyNote> found = (chapterId == null || chapterId.trim().isEmpty())
                ? notes.all(currentUser.require())
                : notes.forChapter(currentUser.require(), chapterId.trim());
        List<NoteResponse> out = new ArrayList<>(found.size());
        for (StickyNote n : found) {
            out.add(NoteResponse.of(n));
        }
        return out;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NoteResponse> create(@Valid @RequestBody NoteRequest request) {
        StickyNote note = notes.create(
                currentUser.require(),
                request.getChapterId() == null ? "ch-start-here" : request.getChapterId(),
                request.getBody(),
                request.getColour());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(note.getId())
                .toUri();
        return ResponseEntity.created(location).body(NoteResponse.of(note));
    }

    /**
     * PATCH, not PUT.
     *
     * <p>PUT means "replace the resource with this", so a PUT missing a field
     * should clear it. Changing only the colour of a note with a PUT would
     * therefore have to resend the body text, and a client that forgot would
     * silently erase it. PATCH means "apply these changes", which is what the
     * pin toggle and the colour picker actually want.
     */
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public NoteResponse update(@PathVariable("id") Long id, @Valid @RequestBody NoteRequest request) {
        return NoteResponse.of(notes.update(
                currentUser.require(), id,
                request.getBody(), request.getColour(),
                request.getPinned(), request.getChapterId()));
    }

    @PostMapping(path = "/{id}/move", consumes = MediaType.APPLICATION_JSON_VALUE)
    public NoteResponse move(@PathVariable("id") Long id, @Valid @RequestBody MoveNoteRequest request) {
        return NoteResponse.of(notes.move(
                currentUser.require(), id, request.getBefore(), request.getAfter()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        notes.delete(currentUser.require(), id);
        return ResponseEntity.noContent().build();
    }
}
