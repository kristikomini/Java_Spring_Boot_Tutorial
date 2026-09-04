package it.unicam.cs.enrollment.fieldbook.security;

import jakarta.enterprise.context.ApplicationScoped;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Makes session tokens, and turns one into the value stored in the database.
 *
 * <h2>Two values, one token</h2>
 * <ul>
 *   <li>The RAW token goes to the browser in a cookie and never touches a
 *       table.</li>
 *   <li>Its SHA-256 goes in the table and never leaves the server.</li>
 * </ul>
 *
 * <p>The asymmetry is the point. A leaked database gives an attacker hashes,
 * which cannot be presented as cookies. The lookup still works because hashing
 * is deterministic: hash the cookie you were handed, look for that string.
 *
 * <p>This is the same shape as password storage with one deliberate difference,
 * and being able to explain the difference is worth more than either fact on
 * its own. A password is low entropy and human chosen, so its hash must be
 * slow, to make guessing expensive. This token is 256 bits from a CSPRNG, so
 * there is nothing to guess - there is no dictionary of likely tokens, and
 * brute force is not a strategy against a search space of 2^256. A slow hash
 * here would buy nothing and would cost a PBKDF2 round trip on every single
 * authenticated request.
 */
@ApplicationScoped
public class TokenMint {

    /** 32 bytes = 256 bits. Comfortably past any brute-force argument. */
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /**
     * A fresh token, URL-safe and without padding so it can sit in a cookie
     * value with no escaping.
     */
    public String mint() {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** The value to store and to look up by. Hex, lower case, always 64 chars. */
    public String fingerprint(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(PasswordHasher.utf8(rawToken));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                // & 0xFF undoes Java's signed byte: without it, 0x8A arrives as
                // -118 and formats as "ffffff8a". A classic and quiet bug.
                String s = Integer.toHexString(b & 0xFF);
                if (s.length() == 1) {
                    hex.append('0');
                }
                hex.append(s);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every JDK. If it is genuinely missing the
            // JVM is broken, and pretending otherwise would be worse.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", impossible);
        }
    }
}
