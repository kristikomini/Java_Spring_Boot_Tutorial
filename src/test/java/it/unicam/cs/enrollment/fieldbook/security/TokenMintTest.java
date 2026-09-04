package it.unicam.cs.enrollment.fieldbook.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenMint")
class TokenMintTest {

    private final TokenMint mint = new TokenMint();

    @Test
    @DisplayName("mints a different token every time")
    void tokensAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            seen.add(mint.mint());
        }
        // Two thousand draws from a 256-bit space colliding would mean the
        // generator is not what it claims to be.
        assertThat(seen).hasSize(2000);
    }

    @Test
    @DisplayName("mints tokens that are safe in a cookie value")
    void tokensAreUrlSafe() {
        String token = mint.mint();
        assertThat(token).matches("[A-Za-z0-9_-]+");
        assertThat(token.length()).isGreaterThanOrEqualTo(40);
    }

    @Test
    @DisplayName("fingerprints deterministically, so a lookup can find the row")
    void fingerprintIsStable() {
        String token = mint.mint();
        assertThat(mint.fingerprint(token)).isEqualTo(mint.fingerprint(token));
    }

    @Test
    @DisplayName("produces a 64 character lower-case hex digest with no sign errors")
    void fingerprintShape() {
        // The & 0xFF in the hex loop exists because a Java byte is signed. If it
        // were missing, high bytes would render as "ffffff8a" and the digest
        // would be longer than 64 characters - which is exactly what this
        // assertion catches.
        for (int i = 0; i < 200; i++) {
            assertThat(mint.fingerprint(mint.mint())).matches("[0-9a-f]{64}");
        }
    }

    @Test
    @DisplayName("gives different fingerprints to different tokens")
    void fingerprintDistinguishes() {
        assertThat(mint.fingerprint("one")).isNotEqualTo(mint.fingerprint("two"));
    }
}
