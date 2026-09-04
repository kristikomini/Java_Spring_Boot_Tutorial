package it.unicam.cs.enrollment.fieldbook.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * What the page is told about the signed-in learner.
 *
 * <h2>Why a response DTO instead of returning the entity</h2>
 * Return {@code LearnerAccount} from a JAX-RS method and JSON-B serialises
 * every readable property it can reach - including {@code passwordHash}. That
 * is not hypothetical: "we accidentally returned the whole user object" is a
 * routine finding, and it is caused by exactly this shortcut.
 *
 * <p>The DTO also decouples the wire format from the schema, so renaming a
 * column does not break every client, and it stops a lazy association being
 * touched after the transaction has closed. Three separate problems, one
 * boring class each time.
 */
public class AccountResponse {

    private Long id;
    private String email;
    private String displayName;
    private List<String> roles;
    private Instant createdAt;
    private Instant lastSeenAt;
    private int streak;
    private int bestStreak;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public int getStreak() { return streak; }
    public void setStreak(int streak) { this.streak = streak; }

    public int getBestStreak() { return bestStreak; }
    public void setBestStreak(int bestStreak) { this.bestStreak = bestStreak; }
}
