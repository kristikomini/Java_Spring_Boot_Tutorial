package it.unicam.cs.enrollment.fieldbook.security;

import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Turns a password into something safe to store, and checks one against it.
 *
 * <h2>The four rules, and why each one exists</h2>
 *
 * <ol>
 *   <li><b>Never store the password.</b> Not encrypted either - encryption is
 *       reversible, and the key is on the same machine as the data. Store a
 *       one-way hash, so that a stolen database yields nothing directly
 *       usable.</li>
 *   <li><b>Salt every password separately.</b> The salt is random, unique per
 *       row, and stored in the clear next to the hash. It is not a secret; its
 *       job is to make sure two people who chose the same password get
 *       different hashes. Without it, one precomputed rainbow table breaks
 *       every account at once, and "how many users share this hash" tells an
 *       attacker which passwords to try first.</li>
 *   <li><b>Make it deliberately slow.</b> This is the part that feels wrong
 *       the first time you meet it. SHA-256 is fast, and fast is exactly the
 *       problem: commodity hardware computes billions of SHA-256 a second, so a
 *       plain hash of a human-chosen password is guessable in minutes. PBKDF2
 *       repeats the hash {@code ITERATIONS} times so that one guess costs
 *       milliseconds. You pay it once per login; an attacker pays it per
 *       guess, several billion times.</li>
 *   <li><b>Compare in constant time.</b> {@code Arrays.equals} returns as soon
 *       as two bytes differ, so how long the comparison took leaks how many
 *       leading bytes were right. That is a timing side channel, and over
 *       enough samples it is enough to reconstruct the value byte by byte.
 *       {@link MessageDigest#isEqual} always looks at every byte.</li>
 * </ol>
 *
 * <h2>Why PBKDF2 here, and what is actually better</h2>
 * The modern recommendation is Argon2id, and after it scrypt and bcrypt. All
 * three are MEMORY-HARD: they need a large working set, which is what stops an
 * attacker getting a thousandfold speed-up by moving the attack to a GPU or an
 * FPGA. PBKDF2 is not memory-hard, so it is the weakest of the four.
 *
 * <p>It is used here for one honest reason: it is in the JDK. Argon2 would mean
 * a third-party dependency, and a learning project where you can read every
 * line of the algorithm path is worth more than the margin. In a real system
 * with a real threat model, take the dependency. What you must never do is the
 * thing this class exists to avoid: a bare {@code MessageDigest.digest} of the
 * password, which is still the most common finding in a first security review.
 *
 * <h2>The stored format</h2>
 * <pre>pbkdf2-sha256$210000$&lt;salt-base64&gt;$&lt;hash-base64&gt;</pre>
 * Self-describing, so the cost can be raised later without invalidating rows
 * that were written under the old parameters: an old hash still says how to
 * verify itself, and {@link #needsRehash} says when it should be upgraded on
 * the next successful login. A bare digest column cannot be migrated at all
 * without asking every user to reset their password.
 */
@ApplicationScoped
public class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";

    /**
     * OWASP's floor for PBKDF2-HMAC-SHA256 at the time of writing. Costs
     * roughly 100-200 ms on a laptop, which is a barely noticeable login and a
     * ruinous brute force.
     *
     * <p>Raise it, never lower it, and re-read the current recommendation
     * rather than trusting this comment: the right number is a function of
     * hardware and therefore has a shelf life.
     */
    private static final int ITERATIONS = 210_000;

    /** 128 bits. There is no reason for a salt to be shorter. */
    private static final int SALT_BYTES = 16;

    /** 256 bits, matching the underlying hash. */
    private static final int HASH_BITS = 256;

    /**
     * {@link SecureRandom} and not {@link java.util.Random}. The latter is a
     * linear congruential generator seeded from the clock: observe a handful of
     * outputs and you can predict every future one. It is fine for shuffling a
     * deck and catastrophic for anything an attacker benefits from guessing.
     */
    private final SecureRandom random = new SecureRandom();

    /**
     * Hash a new password.
     *
     * @param password the plaintext, which this method does not retain
     * @return the self-describing encoded form, safe to store
     */
    public String hash(char[] password) {
        Objects.requireNonNull(password, "password must not be null");
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] digest = pbkdf2(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS
                + "$" + b64.encodeToString(salt)
                + "$" + b64.encodeToString(digest);
    }

    /** Convenience overload. Prefer the {@code char[]} form - see {@link #wipe}. */
    public String hash(String password) {
        return hash(password.toCharArray());
    }

    /**
     * Check a candidate password against a stored hash.
     *
     * <p>Returns {@code false} rather than throwing on a malformed stored
     * value. A corrupt row must not be distinguishable from a wrong password by
     * an outside observer, and it must not take the login endpoint down either.
     */
    public boolean matches(char[] candidate, String stored) {
        if (candidate == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (RuntimeException malformed) {
            return false;
        }
        if (iterations <= 0 || salt.length == 0 || expected.length == 0) {
            return false;
        }
        byte[] actual = pbkdf2(candidate, salt, iterations);
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    public boolean matches(String candidate, String stored) {
        return candidate != null && matches(candidate.toCharArray(), stored);
    }

    /**
     * True when a stored hash was produced with weaker parameters than the
     * current ones. Call it after a SUCCESSFUL login - that is the only moment
     * the plaintext is available to hash again - and silently upgrade the row.
     * This is how a real system raises its cost factor over years without a
     * mass password reset.
     */
    public boolean needsRehash(String stored) {
        if (stored == null) {
            return true;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return true;
        }
        try {
            return Integer.parseInt(parts[1]) < ITERATIONS;
        } catch (NumberFormatException malformed) {
            return true;
        }
    }

    /**
     * Overwrite a password buffer once it is no longer needed.
     *
     * <p>This is why {@code char[]} rather than {@code String}: strings are
     * immutable and interned, so a password held in one sits in the heap until
     * a garbage collection that may never come, and turns up in any heap dump
     * taken in between. A {@code char[]} can be zeroed the moment you are done.
     *
     * <p>Be honest about how much this buys: the password still arrived as a
     * JSON string and has already been a {@code String} somewhere up the stack.
     * The habit is worth having anyway - it is the reason {@code JPasswordField}
     * returns {@code char[]} - and where the boundary genuinely can hand you a
     * buffer, it is a real mitigation.
     */
    public static void wipe(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // Both are configuration failures of the JVM itself, not of this
            // request: PBKDF2WithHmacSHA256 is mandatory in every supported
            // JDK. There is no sensible recovery, so this becomes a 500 rather
            // than being swallowed into a "wrong password".
            throw new IllegalStateException("PBKDF2 is unavailable in this JVM", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** For tests and tooling that want to assert on the configured cost. */
    static int currentIterations() {
        return ITERATIONS;
    }

    /** UTF-8 bytes of a string, used by {@link TokenMint}. Kept here so the
     *  charset is named once rather than defaulted per call site - a default
     *  charset is a platform-dependent bug waiting for a different server. */
    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
