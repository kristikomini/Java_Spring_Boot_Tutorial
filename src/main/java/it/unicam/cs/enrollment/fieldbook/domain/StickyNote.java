package it.unicam.cs.enrollment.fieldbook.domain;

import it.unicam.cs.enrollment.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/**
 * A note the reader wrote, pinned to a chapter.
 *
 * <h2>Why note-taking is in a study tool at all</h2>
 * Not as a filing cabinet. Writing an idea in your own words forces you to
 * decide what it actually said, which is the same retrieval-and-reconstruction
 * work that makes testing beat rereading. A note copied verbatim out of the
 * chapter does none of that, which is why the editor nudges towards short notes
 * and why there is a prompt rather than an empty box.
 *
 * <h2>Storage shape</h2>
 * The body is plain text and is rendered as plain text. It is never interpreted
 * as HTML or Markdown, which removes a whole class of stored cross-site
 * scripting: a note reading {@code <img onerror=...>} is a note about an img
 * tag. Choosing "escape everything" over "sanitise carefully" is nearly always
 * the right trade for user-generated content - sanitisers have bypasses, and
 * escaping does not.
 */
@Entity
@Table(
        name = "fieldbook_notes",
        indexes = {
                @Index(name = "idx_fb_notes_account", columnList = "account_id"),
                @Index(name = "idx_fb_notes_account_chapter", columnList = "account_id, chapter_id")
        }
)
@NamedQuery(
        name = "StickyNote.findByAccount",
        query = "SELECT n FROM StickyNote n WHERE n.account = :account ORDER BY n.sortIndex ASC, n.id ASC"
)
@NamedQuery(
        name = "StickyNote.findByAccountAndChapter",
        query = "SELECT n FROM StickyNote n WHERE n.account = :account AND n.chapterId = :chapterId "
                + "ORDER BY n.sortIndex ASC, n.id ASC"
)
@NamedQuery(
        name = "StickyNote.deleteForAccount",
        query = "DELETE FROM StickyNote n WHERE n.account = :account"
)
public class StickyNote extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Longer than any note anybody sensibly writes on a sticky. */
    public static final int MAX_BODY = 4000;

    /**
     * The five paper colours the page offers. An enum would be tidier Java;
     * a validated string is chosen here because the palette is a presentation
     * decision that belongs to the stylesheet, and every new colour would
     * otherwise be a Java change, a migration and a deploy.
     */
    private static final java.util.Set<String> COLOURS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "amber", "rose", "sage", "sky", "plain"));

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fb_notes_account"))
    private LearnerAccount account;

    /** Chapter slug this note is pinned to. */
    @Column(name = "chapter_id", nullable = false, length = 60)
    private String chapterId;

    /**
     * {@code @Lob} maps to {@code text} on PostgreSQL. A {@code VARCHAR(4000)}
     * would work too; the difference matters mostly when the limit turns out to
     * be wrong, and a text column has no limit to be wrong about.
     */
    @Lob
    @Size(max = MAX_BODY)
    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "colour", nullable = false, length = 12)
    private String colour;

    /** Pinned notes float to the top of the board. */
    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    /**
     * Where the note sits on the board. A float rather than an integer so a
     * note can be dropped between two others by averaging their indices,
     * without renumbering the whole list. This trick - fractional or
     * "lexicographic" ordering - is how every drag-and-drop list you have used
     * avoids writing N rows on every reorder.
     */
    @Column(name = "sort_index", nullable = false)
    private double sortIndex;

    protected StickyNote() {
        // required by JPA
    }

    private StickyNote(LearnerAccount account, String chapterId, String body,
                       String colour, double sortIndex) {
        this.account = account;
        this.chapterId = chapterId;
        this.body = body;
        this.colour = colour;
        this.sortIndex = sortIndex;
    }

    public static StickyNote write(LearnerAccount account, String chapterId,
                                   String body, String colour, double sortIndex) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        return new StickyNote(account, chapterId, clean(body), normaliseColour(colour), sortIndex);
    }

    public void edit(String newBody) {
        this.body = clean(newBody);
    }

    public void recolour(String newColour) {
        this.colour = normaliseColour(newColour);
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public void moveTo(double sortIndex) {
        this.sortIndex = sortIndex;
    }

    public void moveToChapter(String chapterId) {
        this.chapterId = Objects.requireNonNull(chapterId, "chapterId must not be null");
    }

    /**
     * Trim and cap. Validation at the boundary would also catch an over-long
     * body, but a domain object that can be constructed into an invalid state
     * by any caller is a domain object that eventually is. Belt and braces, in
     * the same spirit as the CHECK constraints in the migrations.
     */
    private static String clean(String raw) {
        String text = raw == null ? "" : raw.trim();
        return text.length() > MAX_BODY ? text.substring(0, MAX_BODY) : text;
    }

    private static String normaliseColour(String raw) {
        String c = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return COLOURS.contains(c) ? c : "amber";
    }

    public LearnerAccount getAccount() {
        return account;
    }

    public String getChapterId() {
        return chapterId;
    }

    public String getBody() {
        return body;
    }

    public String getColour() {
        return colour;
    }

    public boolean isPinned() {
        return pinned;
    }

    public double getSortIndex() {
        return sortIndex;
    }
}
