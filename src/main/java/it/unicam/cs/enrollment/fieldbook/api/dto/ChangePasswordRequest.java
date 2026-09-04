package it.unicam.cs.enrollment.fieldbook.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Changing a password requires proving you know the current one, even though
 * the caller is already authenticated.
 *
 * <p>The reason is the unattended laptop: a session cookie proves that this
 * browser was logged in at some point, not that the person at the keyboard is
 * the account holder. Re-asking for the password before a change that would
 * lock the real owner out is the standard mitigation, and it is why every site
 * that does this correctly annoys you in the same way.
 */
public class ChangePasswordRequest {

    @NotBlank
    @Size(max = 200)
    private String currentPassword;

    @NotBlank
    @Size(max = 200)
    private String newPassword;

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }

    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }

    @Override
    public String toString() {
        return "ChangePasswordRequest{}";
    }
}
