package it.unicam.cs.enrollment.fieldbook.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The sign-in form: a username and a password, and no address anywhere.
 *
 * <p>See {@link RegisterRequest} for why {@code toString} is overridden.
 *
 * <h2>Why the shape rule is not repeated here</h2>
 * {@code Username.of} knows what a valid handle looks like; this class only
 * bounds the length, so that a megabyte of "username" is rejected before it
 * reaches a database query. Putting the pattern in a {@code @Pattern} as well
 * would mean two definitions of the same rule, and the login endpoint is the
 * one place where the strict version would be actively harmful: a form that
 * answers "that is not a valid username" has just told an attacker which of
 * their guesses are worth making. Sign-in has exactly one failure message.
 */
public class LoginRequest {

    @NotBlank(message = "A username is required")
    @Size(max = 60)
    private String username;

    @NotBlank(message = "A password is required")
    @Size(max = 200)
    private String password;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    @Override
    public String toString() {
        return "LoginRequest{username=" + username + "}";
    }
}
