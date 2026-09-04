package it.unicam.cs.enrollment.fieldbook.api.dto;

/**
 * Where a dragged note landed: the sort index of the note above it and of the
 * note below it. See {@code NoteService.move} for why an average is enough.
 */
public class MoveNoteRequest {

    private double before;
    private double after;

    public double getBefore() { return before; }
    public void setBefore(double before) { this.before = before; }

    public double getAfter() { return after; }
    public void setAfter(double after) { this.after = after; }
}
