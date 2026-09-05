package it.unicam.cs.enrollment.fieldbook.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "Here is the link you sent me, and my new password" - step two of two.
 *
 * <h2>The token is a credential, and is treated as one</h2>
 * For the hour it is alive this string is as good as the password it replaces,
 * which is why it is handled exactly like one: {@code toString} does not print
 * it, the resource converts it once, and the server stores only its SHA-256.
 * A token that turns up in an access log because somebody put it in a query
 * string has been published to everyone with log access - which is why the
 * page sends it in a body rather than passing through the URL it arrived on.
 *
 * <h2>Why the minimum length is not here either</h2>
 * Same answer as {@link RegisterRequest}: twelve characters is a business rule
 * that lives in {@code AccountService} with the comment explaining the number,
 * and the {@code @Size(max)} here is the denial-of-service guard that stops a
 * ten megabyte "password" reaching PBKDF2.
 */
public class ResetPasswordRequest {

    @NotBlank(message = "The reset link is missing or incomplete")
    @Size(max = 200)
    private String token;

    @NotBlank(message = "A new password is required")
    @Size(max = 200, message = "That password is too long")
    private String newPassword;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }

    /** Neither field is printable. See {@link RegisterRequest#toString()}. */
    @Override
    public String toString() {
        return "ResetPasswordRequest{token=" + (token == null ? "absent" : "present") + "}";
    }
}
