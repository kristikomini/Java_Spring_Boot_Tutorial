package it.unicam.cs.enrollment.fieldbook.service;

import it.unicam.cs.enrollment.common.logging.Loggable;
import it.unicam.cs.enrollment.exception.ResourceNotFoundException;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.fieldbook.domain.StickyNote;
import it.unicam.cs.enrollment.fieldbook.repository.StickyNoteRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Sticky notes: create, edit, move, delete.
 *
 * <h2>Small, and deliberately so</h2>
 * There is almost no logic here, and that is the honest outcome rather than an
 * omission. Notes have no rules: any text, any chapter, no interactions with
 * anything else. A service layer over a repository that adds nothing is a
 * layer people rightly complain about.
 *
 * <p>It exists anyway for exactly two reasons, and they are the only two that
 * ever justify a thin service: it owns the TRANSACTION BOUNDARY, so a resource
 * class never has to think about one, and it owns the OWNERSHIP RULE, so every
 * path to a note goes through {@code findOwned}. Both are properties of the
 * whole use case rather than of a single query, which is what makes this the
 * right place for them.
 *
 * <p>If it ever grows a third reason, that is a feature. If it never does, this
 * is still cheaper than the alternative, which is a REST resource with
 * {@code @Transactional} on it and an ownership check pasted into five methods.
 */
@Loggable
@ApplicationScoped
public class NoteService {

    /** More than anybody needs, and low enough that one account cannot fill a disk. */
    public static final int MAX_NOTES_PER_ACCOUNT = 500;

    private StickyNoteRepository notes;

    /**
     * Required by CDI, and the reason is worth knowing because the error it
     * produces names neither this constructor nor the annotation that needs it.
     *
     * <p>An {@code @ApplicationScoped} bean is NORMAL SCOPED, so what gets
     * injected anywhere is never this object - it is a generated PROXY
     * subclass that forwards to the contextual instance. To generate that
     * subclass the container has to be able to instantiate it, and a subclass
     * can only be instantiated through a superclass constructor it can call
     * with no arguments.
     *
     * <p>Leave it out and the deployment fails, not the compile:
     * <pre>WELD-001435: Normal scoped bean class ... is not proxyable
     * because it has no no-args constructor</pre>
     * reported against whatever injected it rather than against this class.
     *
     * <p>It also forces the fields below to be non-final, since this
     * constructor leaves them unset. That is the cost of the proxy, it is why
     * every service in this codebase looks like this, and it is the concrete
     * form of the point the fieldbook makes about why the container avoids
     * your constructors.
     */
    protected NoteService() {
        // required by CDI
    }

    @Inject
    public NoteService(StickyNoteRepository notes) {
        this.notes = notes;
    }

    @Transactional
    public List<StickyNote> all(LearnerAccount account) {
        return notes.findAllFor(account);
    }

    @Transactional
    public List<StickyNote> forChapter(LearnerAccount account, String chapterId) {
        return notes.findFor(account, chapterId);
    }

    /**
     * A quota, and a plain one.
     *
     * <p>Any endpoint that lets an authenticated caller create rows without a
     * limit is a resource exhaustion bug: not a dramatic one, but the kind that
     * fills a disk at three in the morning. The check belongs here rather than
     * in a database constraint because "how many" is a product decision, and
     * because the error a constraint produces is unreadable.
     */
    @Transactional
    public StickyNote create(LearnerAccount account, String chapterId, String body, String colour) {
        long count = notes.findAllFor(account).size();
        if (count >= MAX_NOTES_PER_ACCOUNT) {
            throw new it.unicam.cs.enrollment.exception.BusinessRuleViolationException(
                    "NOTE_LIMIT_REACHED",
                    "You have reached the limit of " + MAX_NOTES_PER_ACCOUNT
                            + " notes. Delete a few before adding more.");
        }
        StickyNote note = StickyNote.write(
                account, chapterId, body, colour, notes.highestSortIndex(account) + 1);
        return notes.save(note);
    }

    @Transactional
    public StickyNote update(LearnerAccount account, Long noteId,
                             String body, String colour, Boolean pinned, String chapterId) {
        StickyNote note = require(account, noteId);
        if (body != null) {
            note.edit(body);
        }
        if (colour != null) {
            note.recolour(colour);
        }
        if (pinned != null) {
            note.setPinned(pinned);
        }
        if (chapterId != null && !chapterId.isEmpty()) {
            note.moveToChapter(chapterId);
        }
        return note;
    }

    /**
     * Drop a note between two others.
     *
     * <p>The caller sends the sort indices of the notes either side and the new
     * index is their average, so a reorder writes one row instead of
     * renumbering the list. Averaging repeatedly does eventually run out of
     * precision - roughly fifty insertions into the same gap, with doubles -
     * at which point a real implementation renumbers in the background. Worth
     * knowing that the limit exists before you meet it in production.
     */
    @Transactional
    public StickyNote move(LearnerAccount account, Long noteId, double before, double after) {
        StickyNote note = require(account, noteId);
        note.moveTo((before + after) / 2.0);
        return note;
    }

    @Transactional
    public void delete(LearnerAccount account, Long noteId) {
        notes.delete(require(account, noteId));
    }

    private StickyNote require(LearnerAccount account, Long noteId) {
        return notes.findOwned(account, noteId).orElseThrow(() ->
                // 404, not 403. Telling a caller "that note exists but is not
                // yours" confirms the existence of somebody else's row, which
                // is the same information leak as a login form that
                // distinguishes unknown user from wrong password.
                new ResourceNotFoundException("No note with id " + noteId));
    }
}
