package it.unicam.cs.enrollment.fieldbook.api;

import it.unicam.cs.enrollment.fieldbook.api.dto.CheckpointRequest;
import it.unicam.cs.enrollment.fieldbook.api.dto.SyncRequest;
import it.unicam.cs.enrollment.fieldbook.security.Authenticated;
import it.unicam.cs.enrollment.fieldbook.security.CsrfProtected;
import it.unicam.cs.enrollment.fieldbook.security.CurrentUser;
import it.unicam.cs.enrollment.fieldbook.service.ProgressService;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Collections;
import java.util.List;

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
@Path("/fieldbook/progress")
@RequestScoped
@Authenticated
@CsrfProtected
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProgressResource {

    @Inject
    ProgressService progress;

    @Inject
    CurrentUser currentUser;

    /**
     * The current snapshot.
     *
     * <p>The chapter catalogue arrives as a repeated query parameter, because a
     * GET has no body. It is optional: with no catalogue the mastery percentage
     * comes back as zero rather than as an error, since a caller that only
     * wants the raw cards should not have to describe the whole course to get
     * them.
     */
    @GET
    public ProgressService.Snapshot snapshot(@QueryParam("chapter") List<String> catalogue,
                                             @QueryParam("checkpoint") List<String> withCheckpoint) {
        return progress.snapshot(
                currentUser.require(),
                catalogue == null ? Collections.emptyList() : catalogue,
                withCheckpoint == null ? Collections.emptyList() : withCheckpoint);
    }

    @PUT
    public ProgressService.Snapshot sync(@Valid @NotNull SyncRequest request) {
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
    @POST
    @Path("/checkpoint")
    public Response checkpoint(@Valid @NotNull CheckpointRequest request) {
        boolean firstPass = progress.recordCheckpoint(
                currentUser.require(), request.getChapterId(), request.getScore());
        return Response.ok(Collections.singletonMap("firstPass", firstPass)).build();
    }

    @POST
    @Path("/read")
    public Response markRead(@QueryParam("chapter") String chapterId) {
        if (chapterId == null || chapterId.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        progress.markRead(currentUser.require(), chapterId.trim());
        return Response.noContent().build();
    }

    /**
     * Start the course again.
     *
     * <p>A DELETE that wipes months of study deserves more than an accidental
     * click, so the client asks twice. Server side there is no undo and none is
     * pretended: the honest design is a confirmation, not a fake recycle bin.
     */
    @DELETE
    public Response reset() {
        int wiped = progress.reset(currentUser.require());
        return Response.ok(Collections.singletonMap("removed", wiped)).build();
    }
}
