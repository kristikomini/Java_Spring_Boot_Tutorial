package it.unicam.cs.enrollment.fieldbook.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule this class enforces is now almost entirely about NORMALISATION
 * rather than about shape, and these tests are split along that line: what is
 * still refused, and what is quietly rewritten on the way in.
 *
 * <p>The second half is the half that matters. A validation rule that rejects
 * announces itself the moment it is wrong; a normalisation rule that folds two
 * inputs into one value fails silently, as a duplicate account nobody can
 * explain, so it is worth pinning down explicitly.
 */
@DisplayName("Username")
class UsernameTest {

    @Nested
    @DisplayName("accepts")
    class Accepts {

        @Test
        @DisplayName("a single character")
        void oneCharacter() {
            assertThat(Username.of("m").getValue()).isEqualTo("m");
        }

        @Test
        @DisplayName("exactly the column width")
        void thirtyCharacters() {
            String thirty = "abcdefghijabcdefghijabcdefghij";
            assertThat(thirty).hasSize(Username.MAX_LENGTH);
            assertThat(Username.of(thirty).getValue()).isEqualTo(thirty);
        }

        @Test
        @DisplayName("spaces, accents, punctuation and emoji")
        void anythingVisible() {
            // Every one of these was refused by the old shape rule. None of
            // them is a problem: the value is escaped everywhere it is
            // rendered, and it is a parameter everywhere it reaches SQL.
            assertThat(Username.isValid("mario rossi")).isTrue();
            assertThat(Username.isValid("mariò")).isTrue();
            assertThat(Username.isValid(".mario.")).isTrue();
            assertThat(Username.isValid("m@ri0!")).isTrue();
            assertThat(Username.isValid("l'unicam")).isTrue();
            assertThat(Username.isValid("mario\uD83D\uDE00")).isTrue();  // an emoji
        }

        @Test
        @DisplayName("a handle that would be markup, without altering it")
        void doesNotSanitise() {
            // Deliberately stored verbatim rather than stripped or escaped
            // here. Escaping belongs at the point of rendering, where the
            // target syntax is known; a value object that pre-escapes for HTML
            // is a value object that is wrong in a log file, a CSV and a JSON
            // body. See the sticky-note note in docs/ACCOUNTS.md.
            assertThat(Username.of("<b>mario</b>").getValue()).isEqualTo("<b>mario</b>");
        }
    }

    @Nested
    @DisplayName("refuses")
    class Refuses {

        @Test
        @DisplayName("blank, in every form of blank")
        void blank() {
            assertThat(Username.isValid("")).isFalse();
            assertThat(Username.isValid("   ")).isFalse();
            assertThat(Username.isValid(null)).isFalse();
            assertThatThrownBy(() -> Username.of("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("anything past the column width")
        void tooLong() {
            String thirtyOne = "abcdefghijabcdefghijabcdefghijk";
            assertThat(thirtyOne).hasSize(Username.MAX_LENGTH + 1);
            // Not a matter of taste: the column is VARCHAR(30), so accepting
            // this would turn a clear rejection into a constraint violation
            // thrown from inside a transaction.
            assertThatThrownBy(() -> Username.of(thirtyOne))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("characters that cannot be seen")
        void invisibleCharacters() {
            // The whole of what is left of the old character rule, and the part
            // that was load-bearing: these render as nothing, so two handles
            // that look identical on screen are different strings in the
            // database and nobody can tell them apart to report it.
            //
            // Written as escapes rather than pasted, because pasted they
            // produce a source file that appears to test "mario" five times
            // against a reviewer who cannot see the difference.
            assertThat(Username.isValid("mar\u200Bio")).isFalse();  // zero-width space
            assertThat(Username.isValid("mario\u202E")).isFalse();  // right-to-left override
            assertThat(Username.isValid("mar\u00ADio")).isFalse();  // soft hyphen
            assertThat(Username.isValid("mar\u0000io")).isFalse();  // NUL
            assertThat(Username.isValid("mar\tio")).isFalse();      // tab

            // And the boundary: a handle made only of invisible characters
            // reads as blank but is not, because trim() does not strip these.
            // It is this rule, not the blank check, that catches it.
            assertThat(Username.isValid("\u200B\u200B")).isFalse();
        }
    }

    @Nested
    @DisplayName("normalises")
    class Normalises {

        @Test
        @DisplayName("case, so the unique constraint means what a human expects")
        void lowerCases() {
            assertThat(Username.of("MARIO").getValue()).isEqualTo("mario");
            assertThat(Username.of("Mario")).isEqualTo(Username.of("mario"));
        }

        @Test
        @DisplayName("surrounding whitespace, which is usually a paste")
        void trims() {
            assertThat(Username.of("  mario  ").getValue()).isEqualTo("mario");
        }

        @Test
        @DisplayName("before measuring the length, not after")
        void trimsBeforeLengthCheck() {
            // Otherwise a pasted handle with trailing spaces is rejected for
            // being too long and then, retyped identically, accepted.
            String padded = "   abcdefghijabcdefghijabcdefghij   ";
            assertThat(padded.length()).isGreaterThan(Username.MAX_LENGTH);
            assertThat(Username.of(padded).getValue()).hasSize(Username.MAX_LENGTH);
        }
    }
}
