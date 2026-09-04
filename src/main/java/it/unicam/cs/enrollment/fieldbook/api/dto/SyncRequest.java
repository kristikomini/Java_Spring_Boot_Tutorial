package it.unicam.cs.enrollment.fieldbook.api.dto;

import it.unicam.cs.enrollment.fieldbook.service.ProgressService;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the browser knows, sent up in one request.
 *
 * <h2>Why the client sends the course structure</h2>
 * {@code catalogue} and {@code withCheckpoint} describe the chapters and which
 * of them have an end-of-chapter test. The server stores neither.
 *
 * <p>That looks backwards until you notice where the course actually lives: the
 * chapters and their questions are in one HTML file, which is what makes the
 * fieldbook work with no server at all. If the server also held a chapter list
 * there would be two copies of the same truth, they would drift, and the day
 * they drifted every learner would see a quietly wrong percentage. One
 * authoritative copy, passed to whoever needs it, is the cheaper design even
 * though it means a larger request body.
 *
 * <p>The trade-off is that a hostile client can lie about the catalogue and
 * change its own percentage. That is accepted deliberately: the number is a
 * study aid for the person looking at it, not a grade anybody else relies on.
 * The moment it becomes a credential - a certificate, a mark - this design is
 * wrong and the catalogue has to move server-side. Being able to say which of
 * those two situations you are in is the whole of the decision.
 */
public class SyncRequest {

    /** Chapter slugs, in course order. */
    @Size(max = 500)
    private List<String> catalogue = new ArrayList<>();

    /** Which of them have a checkpoint. */
    @Size(max = 500)
    private List<String> withCheckpoint = new ArrayList<>();

    /** Card states changed since the last sync. */
    @Size(max = 5000, message = "Too many cards in one sync")
    private List<ProgressService.CardState> cards = new ArrayList<>();

    /** Chapter states changed since the last sync. */
    @Size(max = 500)
    private List<ProgressService.ChapterState> chapters = new ArrayList<>();

    public List<String> getCatalogue() { return catalogue; }
    public void setCatalogue(List<String> catalogue) { this.catalogue = catalogue; }

    public List<String> getWithCheckpoint() { return withCheckpoint; }
    public void setWithCheckpoint(List<String> withCheckpoint) { this.withCheckpoint = withCheckpoint; }

    public List<ProgressService.CardState> getCards() { return cards; }
    public void setCards(List<ProgressService.CardState> cards) { this.cards = cards; }

    public List<ProgressService.ChapterState> getChapters() { return chapters; }
    public void setChapters(List<ProgressService.ChapterState> chapters) { this.chapters = chapters; }
}
