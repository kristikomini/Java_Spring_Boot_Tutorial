package it.unicam.cs.enrollment.fieldbook.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Create or update a sticky note.
 *
 * <p>Every field is optional on update, and {@code null} means "leave it
 * alone" rather than "set it to null". That is the PATCH semantic, and it is
 * worth being explicit about because the other reading - absent means clear -
 * turns a partial update into accidental data loss. JSON cannot distinguish
 * "absent" from "explicitly null" without extra machinery, so an API that needs
 * the distinction has to say which one it means.
 */
public class NoteRequest {

    @Size(max = 60)
    private String chapterId;

    @Size(max = 4000, message = "That note is too long")
    private String body;

    @Size(max = 12)
    private String colour;

    private Boolean pinned;

    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getColour() { return colour; }
    public void setColour(String colour) { this.colour = colour; }

    public Boolean getPinned() { return pinned; }
    public void setPinned(Boolean pinned) { this.pinned = pinned; }
}
