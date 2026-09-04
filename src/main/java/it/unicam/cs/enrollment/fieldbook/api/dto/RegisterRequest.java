package it.unicam.cs.enrollment.fieldbook.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The registration form, as JSON.
 *
 * <h2>Why the password is a String here and a char[] two layers down</h2>
 * Because JSON-B hands you a {@code String} and there is no way to ask it not
 * to. Declaring {@code char[]} would produce a base64-decoded byte array, not a
 * password. The realistic approach is to accept that the boundary layer holds a
 * {@code String}, convert it once, and wipe from there inwards - which is what
 * {@code AuthResource} does. Security advice that cannot be followed at the
 * boundary is worse than useless: people conclude the whole idea is theatre.
 *
 * <h2>Why {@code @Size(max)} and not {@code @Size(min)}</h2>
 * The minimum length is a business rule and lives in {@code AccountService},
 * where it is one constant with a comment explaining the number. The maximum is
 * here because it is a denial-of-service guard: PBKDF2 over a ten megabyte
 * "password" is ten megabytes of hashing, and rejecting that before it reaches
 * any expensive code is exactly what boundary validation is for.
 */
public class RegisterRequest {

    @NotBlank(message = "An email address is required")
    @Size(max = 255)
    private String email;

    @Size(max = 60)
    private String displayName;

    @NotBlank(message = "A password is required")
    @Size(max = 200, message = "That password is too long")
    private String password;

    /** IANA zone id from the browser, for example Europe/Rome. Optional. */
    @Size(max = 60)
    private String timeZone;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }

    /**
     * Deliberately does NOT include the password.
     *
     * <p>A DTO whose {@code toString} prints its credentials will eventually be
     * logged by something - an exception handler, a debug statement, a
     * framework being helpful - and then the password is in a log file that
     * gets shipped to a search index and kept for a year. This is one of the
     * most common ways plaintext passwords reach production systems, and the
     * fix is four lines in a class nobody reads.
     */
    @Override
    public String toString() {
        return "RegisterRequest{email=" + email + ", displayName=" + displayName + "}";
    }
}
