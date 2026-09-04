package it.unicam.cs.enrollment.fieldbook.api;

import it.unicam.cs.enrollment.fieldbook.api.dto.MoveNoteRequest;
import it.unicam.cs.enrollment.fieldbook.api.dto.NoteRequest;
import it.unicam.cs.enrollment.fieldbook.api.dto.NoteResponse;
import it.unicam.cs.enrollment.fieldbook.domain.StickyNote;
import it.unicam.cs.enrollment.fieldbook.security.Authenticated;
import it.unicam.cs.enrollment.fieldbook.security.CsrfProtected;
import it.unicam.cs.enrollment.fieldbook.security.CurrentUser;
import it.unicam.cs.enrollment.fieldbook.service.NoteService;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

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
@Path("/fieldbook/notes")
@RequestScoped
@Authenticated
@CsrfProtected
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NoteResource {

    @Inject
    NoteService notes;

    @Inject
    CurrentUser currentUser;

    @Context
    UriInfo uriInfo;

    /** All of them, or just one chapter. */
    @GET
    public List<NoteResponse> list(@QueryParam("chapter") String chapterId) {
        List<StickyNote> found = (chapterId == null || chapterId.trim().isEmpty())
                ? notes.all(currentUser.require())
                : notes.forChapter(currentUser.require(), chapterId.trim());
        List<NoteResponse> out = new ArrayList<>(found.size());
        for (StickyNote n : found) {
            out.add(NoteResponse.of(n));
        }
        return out;
    }

    @POST
    public Response create(@Valid @NotNull NoteRequest request) {
        StickyNote note = notes.create(
                currentUser.require(),
                request.getChapterId() == null ? "ch-start-here" : request.getChapterId(),
                request.getBody(),
                request.getColour());
        URI location = uriInfo.getAbsolutePathBuilder().path(String.valueOf(note.getId())).build();
        return Response.created(location).entity(NoteResponse.of(note)).build();
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
    @PATCH
    @Path("/{id}")
    public NoteResponse update(@PathParam("id") Long id, @Valid @NotNull NoteRequest request) {
        return NoteResponse.of(notes.update(
                currentUser.require(), id,
                request.getBody(), request.getColour(),
                request.getPinned(), request.getChapterId()));
    }

    @POST
    @Path("/{id}/move")
    public NoteResponse move(@PathParam("id") Long id, @Valid @NotNull MoveNoteRequest request) {
        return NoteResponse.of(notes.move(
                currentUser.require(), id, request.getBefore(), request.getAfter()));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") Long id) {
        notes.delete(currentUser.require(), id);
        return Response.noContent().build();
    }
}
