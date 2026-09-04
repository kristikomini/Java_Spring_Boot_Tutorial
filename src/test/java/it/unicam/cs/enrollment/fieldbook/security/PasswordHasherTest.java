package it.unicam.cs.enrollment.fieldbook.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hasher is the one class here where being wrong is silent: a broken
 * implementation still returns a string, still stores it, and still lets people
 * log in. Nothing fails until somebody steals the table.
 *
 * <p>So these tests assert the properties rather than the output. There is no
 * expected hash to compare against - a salted hash is different every time,
 * which is the first test below.
 */
@DisplayName("PasswordHasher")
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    @DisplayName("accepts the password it hashed")
    void roundTrip() {
        String stored = hasher.hash("correct horse battery staple".toCharArray());
        assertThat(hasher.matches("correct horse battery staple".toCharArray(), stored)).isTrue();
    }

    @Test
    @DisplayName("rejects a wrong password, including one that differs by a single character")
    void rejectsWrong() {
        String stored = hasher.hash("correct horse battery staple".toCharArray());
        assertThat(hasher.matches("correct horse battery stapl".toCharArray(), stored)).isFalse();
        assertThat(hasher.matches("".toCharArray(), stored)).isFalse();
    }

    @Test
    @DisplayName("hashes the same password differently every time, because of the salt")
    void saltsEachHash() {
        String a = hasher.hash("the same password".toCharArray());
        String b = hasher.hash("the same password".toCharArray());

        // If this fails, the salt is not random or is not being used, and every
        // user who chose the same password is now identifiable from the table.
        assertThat(a).isNotEqualTo(b);
        assertThat(hasher.matches("the same password".toCharArray(), a)).isTrue();
        assertThat(hasher.matches("the same password".toCharArray(), b)).isTrue();
    }

    @Test
    @DisplayName("stores the algorithm and cost alongside the digest")
    void formatIsSelfDescribing() {
        String stored = hasher.hash("a password long enough".toCharArray());
        String[] parts = stored.split("\\$");

        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("pbkdf2-sha256");
        assertThat(Integer.parseInt(parts[1])).isGreaterThanOrEqualTo(100_000);
    }

    @Test
    @DisplayName("returns false rather than throwing on a corrupt stored value")
    void malformedStoredValue() {
        // A corrupt row must look exactly like a wrong password from outside,
        // and must not take the login endpoint down.
        assertThat(hasher.matches("anything".toCharArray(), "not-a-hash")).isFalse();
        assertThat(hasher.matches("anything".toCharArray(), "pbkdf2-sha256$abc$def$ghi")).isFalse();
        assertThat(hasher.matches("anything".toCharArray(), "")).isFalse();
        assertThat(hasher.matches("anything".toCharArray(), null)).isFalse();
        assertThat(hasher.matches((char[]) null, "whatever")).isFalse();
    }

    @Test
    @DisplayName("flags a hash written with a lower cost for upgrade")
    void needsRehashWhenCostIsRaised() {
        String current = hasher.hash("a password long enough".toCharArray());
        assertThat(hasher.needsRehash(current)).isFalse();

        // A row written under an older, cheaper configuration.
        String old = "pbkdf2-sha256$1000$c2FsdA==$ZGlnZXN0";
        assertThat(hasher.needsRehash(old)).isTrue();
        assertThat(hasher.needsRehash("rubbish")).isTrue();
        assertThat(hasher.needsRehash(null)).isTrue();
    }

    @Test
    @DisplayName("wipe zeroes the buffer it is given")
    void wipeClearsTheArray() {
        char[] secret = "hunter2hunter2".toCharArray();
        PasswordHasher.wipe(secret);
        assertThat(secret).containsOnly('\0');
    }
}
