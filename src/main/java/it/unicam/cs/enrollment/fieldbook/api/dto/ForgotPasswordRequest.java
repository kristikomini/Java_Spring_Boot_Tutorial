package it.unicam.cs.enrollment.fieldbook.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "I have forgotten my password" - step one of two.
 *
 * <h2>Why this asks for the email and not the username</h2>
 * Because the answer has to be delivered somewhere, and a username is not a
 * destination. Asking for the handle and looking its address up would work, and
 * it would mean the form only helps somebody who remembers the half of their
 * credentials this flow exists to recover.
 *
 * <p>The cost is that a person who has forgotten their username gets no help
 * here. That is handled by the reset email itself, which names the account it
 * belongs to - so one round trip recovers both. It is worth noticing that this
 * is a product decision made in a DTO's javadoc: which field a form asks for
 * decides which kind of forgetting it can repair.
 *
 * <h2>What the endpoint may not do with it</h2>
 * The response cannot depend on whether the address is registered. That is not
 * a preference; a form that answers differently for the two cases is a public
 * API for "does this person have an account here", and this one is anonymous
 * and unauthenticated. See {@code AccountService.requestPasswordReset}.
 */
public class ForgotPasswordRequest {

    @NotBlank(message = "An email address is required")
    @Size(max = 255)
    private String email;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    /**
     * An address is personal data, and this object reaches a log the moment
     * anything upstream decides to be helpful about a failed request. There is
     * no password on it to leak, but the address is the whole payload, so it is
     * printed as its presence rather than its value.
     */
    @Override
    public String toString() {
        return "ForgotPasswordRequest{email=" + (email == null ? "absent" : "present") + "}";
    }
}
