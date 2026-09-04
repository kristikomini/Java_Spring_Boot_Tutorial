package it.unicam.cs.enrollment.fieldbook.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One end-of-chapter checkpoint attempt.
 *
 * <p>{@code @Min(0) @Max(100)} appears here as well as inside
 * {@code ChapterProgress}, and the duplication is on purpose. The annotation
 * rejects a bad request with a readable 400 before any code runs; the check
 * inside the entity stops a future caller - a data import, a test, a second
 * endpoint - from writing nonsense that never passed through this class.
 * Validation at the boundary is for the user; validation in the domain is for
 * the program.
 */
public class CheckpointRequest {

    @NotBlank
    @Size(max = 60)
    private String chapterId;

    @Min(0)
    @Max(100)
    private int score;

    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
}
