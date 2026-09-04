package it.unicam.cs.enrollment.fieldbook.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The sign-in form. See {@link RegisterRequest} for why {@code toString} is overridden. */
public class LoginRequest {

    @NotBlank(message = "An email address is required")
    @Size(max = 255)
    private String email;

    @NotBlank(message = "A password is required")
    @Size(max = 200)
    private String password;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    @Override
    public String toString() {
        return "LoginRequest{email=" + email + "}";
    }
}
