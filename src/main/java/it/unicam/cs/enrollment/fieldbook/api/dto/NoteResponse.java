package it.unicam.cs.enrollment.fieldbook.api.dto;

import it.unicam.cs.enrollment.fieldbook.domain.StickyNote;

import java.time.Instant;

/** A sticky note on the wire. */
public class NoteResponse {

    private Long id;
    private String chapterId;
    private String body;
    private String colour;
    private boolean pinned;
    private double sortIndex;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * A static factory on the DTO rather than a separate mapper class.
     *
     * <p>The enrollment side of this codebase uses dedicated {@code *Mapper}
     * beans, which is the right shape once a mapping has choices to make -
     * which associations to include, which shape to flatten. This one has no
     * choices, and a whole injected bean to copy eight fields would be
     * ceremony. Consistency is worth a lot; it is not worth more than the thing
     * it exists to make clearer.
     */
    public static NoteResponse of(StickyNote note) {
        NoteResponse r = new NoteResponse();
        r.id = note.getId();
        r.chapterId = note.getChapterId();
        r.body = note.getBody();
        r.colour = note.getColour();
        r.pinned = note.isPinned();
        r.sortIndex = note.getSortIndex();
        r.createdAt = note.getCreatedAt();
        r.updatedAt = note.getUpdatedAt();
        return r;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getColour() { return colour; }
    public void setColour(String colour) { this.colour = colour; }

    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public double getSortIndex() { return sortIndex; }
    public void setSortIndex(double sortIndex) { this.sortIndex = sortIndex; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
