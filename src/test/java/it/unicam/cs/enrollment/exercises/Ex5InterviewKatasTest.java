package it.unicam.cs.enrollment.exercises;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;

/**
 * Specification for Exercise 5 - the ten interview katas.
 *
 * <p>Every kata gets its own {@link Nested} block so the failure output reads as
 * a scoreboard: you can see at a glance which of the ten are done. Within each
 * block the first test is the happy path and the rest are the edge cases an
 * interviewer will reach for - empty input, one element, {@code null},
 * duplicates, overflow.
 *
 * <p>Read the block before you write the method. The failing test is the
 * specification, and in an interview the edge cases <em>are</em> the marks.
 */
@Tag("exercise")
@DisplayName("Exercise 5: the ten interview katas")
class Ex5InterviewKatasTest {

    @Nested
    @DisplayName("1 · reverse")
    class Reverse {

        @Test
        @DisplayName("reverses the characters")
        void reverses() {
            assertThat(Ex5InterviewKatas.reverse("enrollment")).isEqualTo("tnemllorne");
        }

        @Test
        @DisplayName("a single character reverses to itself")
        void single() {
            assertThat(Ex5InterviewKatas.reverse("a")).isEqualTo("a");
        }

        @Test
        @DisplayName("empty in, empty out")
        void empty() {
            assertThat(Ex5InterviewKatas.reverse("")).isEmpty();
        }

        @Test
        @DisplayName("null in, null out")
        void nullSafe() {
            assertThat(Ex5InterviewKatas.reverse(null)).isNull();
        }
    }

    @Nested
    @DisplayName("2 · isPalindrome")
    class Palindrome {

        @ParameterizedTest(name = "\"{0}\" is a palindrome")
        @ValueSource(strings = {"racecar", "A man, a plan, a canal: Panama", "Otto", "a", "", "!!!"})
        void palindromes(String input) {
            assertThat(Ex5InterviewKatas.isPalindrome(input)).isTrue();
        }

        @ParameterizedTest(name = "\"{0}\" is not a palindrome")
        @ValueSource(strings = {"enrollment", "ab", "Panama canal"})
        void notPalindromes(String input) {
            assertThat(Ex5InterviewKatas.isPalindrome(input)).isFalse();
        }

        @Test
        @DisplayName("null is not a palindrome")
        void nullIsNot() {
            assertThat(Ex5InterviewKatas.isPalindrome(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("3 · findDuplicates")
    class Duplicates {

        @Test
        @DisplayName("reports each duplicated value once, in discovery order")
        void reportsOnce() {
            assertThat(Ex5InterviewKatas.findDuplicates(new int[]{3, 1, 4, 1, 5, 3, 3}))
                    .containsExactly(1, 3);
        }

        @Test
        @DisplayName("no duplicates means an empty list")
        void none() {
            assertThat(Ex5InterviewKatas.findDuplicates(new int[]{1, 2, 3})).isEmpty();
        }

        @Test
        @DisplayName("empty and null both give an empty list, never null")
        void emptyAndNull() {
            assertThat(Ex5InterviewKatas.findDuplicates(new int[]{})).isNotNull().isEmpty();
            assertThat(Ex5InterviewKatas.findDuplicates(null)).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("negative values and zero behave like any other value")
        void negatives() {
            assertThat(Ex5InterviewKatas.findDuplicates(new int[]{0, -2, 0, -2, -2}))
                    .containsExactly(0, -2);
        }
    }

    @Nested
    @DisplayName("4 · firstNonRepeated")
    class FirstNonRepeated {

        @Test
        @DisplayName("finds the first character occurring exactly once")
        void finds() {
            assertThat(Ex5InterviewKatas.firstNonRepeated("swiss")).isEqualTo('w');
        }

        @Test
        @DisplayName("the answer can be the last character")
        void last() {
            assertThat(Ex5InterviewKatas.firstNonRepeated("aabbc")).isEqualTo('c');
        }

        @Test
        @DisplayName("case matters: 'a' and 'A' are different characters")
        void caseSensitive() {
            assertThat(Ex5InterviewKatas.firstNonRepeated("aA")).isEqualTo('a');
        }

        @Test
        @DisplayName("null when every character repeats, or the input is empty or null")
        void none() {
            assertThat(Ex5InterviewKatas.firstNonRepeated("aabb")).isNull();
            assertThat(Ex5InterviewKatas.firstNonRepeated("")).isNull();
            assertThat(Ex5InterviewKatas.firstNonRepeated(null)).isNull();
        }
    }

    @Nested
    @DisplayName("5 · wordFrequencies")
    class WordFrequencies {

        @Test
        @DisplayName("counts words, lower-cased")
        void counts() {
            assertThat(Ex5InterviewKatas.wordFrequencies("the course the seat The exam"))
                    .containsOnly(entry("the", 3), entry("course", 1),
                                  entry("seat", 1), entry("exam", 1));
        }

        @Test
        @DisplayName("strips leading and trailing punctuation")
        void punctuation() {
            assertThat(Ex5InterviewKatas.wordFrequencies("Full! Course, full."))
                    .containsOnly(entry("full", 2), entry("course", 1));
        }

        @Test
        @DisplayName("runs of whitespace do not create empty keys")
        void whitespace() {
            Map<String, Integer> counts = Ex5InterviewKatas.wordFrequencies("  seat   \t seat \n ");
            assertThat(counts).containsOnly(entry("seat", 2));
            assertThat(counts).doesNotContainKey("");
        }

        @Test
        @DisplayName("null and blank give an empty map, never null")
        void emptyAndNull() {
            assertThat(Ex5InterviewKatas.wordFrequencies(null)).isNotNull().isEmpty();
            assertThat(Ex5InterviewKatas.wordFrequencies("   ")).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("6 · findMissingNumber")
    class MissingNumber {

        @Test
        @DisplayName("finds the gap in the middle")
        void middle() {
            assertThat(Ex5InterviewKatas.findMissingNumber(new int[]{3, 1, 5, 2})).isEqualTo(4);
        }

        @Test
        @DisplayName("finds a missing first or last value")
        void ends() {
            assertThat(Ex5InterviewKatas.findMissingNumber(new int[]{2, 3, 4})).isEqualTo(1);
            assertThat(Ex5InterviewKatas.findMissingNumber(new int[]{1, 2, 3})).isEqualTo(4);
        }

        @Test
        @DisplayName("an empty array means n is 1 and 1 is missing")
        void empty() {
            assertThat(Ex5InterviewKatas.findMissingNumber(new int[]{})).isEqualTo(1);
        }

        @Test
        @DisplayName("does not overflow on a large range")
        void doesNotOverflow() {
            // n = 100_000: the expected sum is 5_000_050_000, which does not fit
            // in an int. A naive int accumulator wraps and gives a wrong answer
            // rather than throwing - the worst kind of bug.
            int n = 100_000;
            int missing = 77_777;
            int[] values = new int[n - 1];
            int at = 0;
            for (int value = 1; value <= n; value++) {
                if (value != missing) {
                    values[at++] = value;
                }
            }
            assertThat(Ex5InterviewKatas.findMissingNumber(values)).isEqualTo(missing);
        }

        @Test
        @DisplayName("null is not valid input")
        void nullRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Ex5InterviewKatas.findMissingNumber(null));
        }
    }

    @Nested
    @DisplayName("7 · mergeSorted")
    class MergeSorted {

        @Test
        @DisplayName("merges two ascending arrays")
        void merges() {
            assertThat(Ex5InterviewKatas.mergeSorted(new int[]{1, 4, 9}, new int[]{2, 3, 10}))
                    .containsExactly(1, 2, 3, 4, 9, 10);
        }

        @Test
        @DisplayName("keeps duplicates from both sides")
        void duplicates() {
            assertThat(Ex5InterviewKatas.mergeSorted(new int[]{1, 2, 2}, new int[]{2, 3}))
                    .containsExactly(1, 2, 2, 2, 3);
        }

        @Test
        @DisplayName("drains whichever side is longer")
        void unevenLengths() {
            assertThat(Ex5InterviewKatas.mergeSorted(new int[]{1}, new int[]{2, 3, 4, 5}))
                    .containsExactly(1, 2, 3, 4, 5);
            assertThat(Ex5InterviewKatas.mergeSorted(new int[]{1, 2, 3, 4}, new int[]{5}))
                    .containsExactly(1, 2, 3, 4, 5);
        }

        @Test
        @DisplayName("empty and null sides are treated as nothing to merge")
        void emptyAndNull() {
            assertThat(Ex5InterviewKatas.mergeSorted(new int[]{1, 2}, new int[]{})).containsExactly(1, 2);
            assertThat(Ex5InterviewKatas.mergeSorted(null, new int[]{1, 2})).containsExactly(1, 2);
            assertThat(Ex5InterviewKatas.mergeSorted(null, null)).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("8 · binarySearch")
    class BinarySearch {

        @ParameterizedTest(name = "finds {0} at index {1}")
        @CsvSource({"1, 0", "3, 1", "5, 2", "7, 3", "9, 4"})
        void finds(int target, int expectedIndex) {
            assertThat(Ex5InterviewKatas.binarySearch(new int[]{1, 3, 5, 7, 9}, target))
                    .isEqualTo(expectedIndex);
        }

        @Test
        @DisplayName("-1 when the target is absent, below the range, or above it")
        void absent() {
            int[] sorted = {1, 3, 5, 7, 9};
            assertThat(Ex5InterviewKatas.binarySearch(sorted, 4)).isEqualTo(-1);
            assertThat(Ex5InterviewKatas.binarySearch(sorted, 0)).isEqualTo(-1);
            assertThat(Ex5InterviewKatas.binarySearch(sorted, 99)).isEqualTo(-1);
        }

        @Test
        @DisplayName("single element, empty and null arrays")
        void degenerate() {
            assertThat(Ex5InterviewKatas.binarySearch(new int[]{42}, 42)).isZero();
            assertThat(Ex5InterviewKatas.binarySearch(new int[]{42}, 7)).isEqualTo(-1);
            assertThat(Ex5InterviewKatas.binarySearch(new int[]{}, 1)).isEqualTo(-1);
            assertThat(Ex5InterviewKatas.binarySearch(null, 1)).isEqualTo(-1);
        }

        @Test
        @DisplayName("works on a large array, where a naive midpoint would overflow")
        void large() {
            int[] sorted = new int[1_000_000];
            for (int i = 0; i < sorted.length; i++) {
                sorted[i] = i * 2;
            }
            assertThat(Ex5InterviewKatas.binarySearch(sorted, 1_999_998)).isEqualTo(999_999);
        }
    }

    @Nested
    @DisplayName("9 · secondHighest")
    class SecondHighest {

        @Test
        @DisplayName("returns the second-largest value")
        void second() {
            assertThat(Ex5InterviewKatas.secondHighest(new int[]{4, 9, 1, 7})).isEqualTo(7);
        }

        @Test
        @DisplayName("distinct: [5, 5, 3] is 3, not 5")
        void distinct() {
            assertThat(Ex5InterviewKatas.secondHighest(new int[]{5, 5, 3})).isEqualTo(3);
        }

        @Test
        @DisplayName("handles negatives")
        void negatives() {
            assertThat(Ex5InterviewKatas.secondHighest(new int[]{-1, -5, -3})).isEqualTo(-3);
        }

        @Test
        @DisplayName("fewer than two distinct values is an error, not an invented answer")
        void tooFew() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Ex5InterviewKatas.secondHighest(new int[]{7, 7, 7}));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Ex5InterviewKatas.secondHighest(new int[]{1}));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Ex5InterviewKatas.secondHighest(new int[]{}));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Ex5InterviewKatas.secondHighest(null));
        }
    }

    @Nested
    @DisplayName("10 · isBalanced")
    class Balanced {

        @ParameterizedTest(name = "\"{0}\" is balanced")
        @ValueSource(strings = {"()", "([]{})", "a(b[c]{d})e", "", "no brackets here"})
        void balanced(String input) {
            assertThat(Ex5InterviewKatas.isBalanced(input)).isTrue();
        }

        @ParameterizedTest(name = "\"{0}\" is not balanced")
        @ValueSource(strings = {"(", ")", ")(", "([)]", "(()", "())"})
        void unbalanced(String input) {
            assertThat(Ex5InterviewKatas.isBalanced(input)).isFalse();
        }

        @Test
        @DisplayName("null is balanced, because there is nothing unmatched in it")
        void nullIsBalanced() {
            assertThat(Ex5InterviewKatas.isBalanced(null)).isTrue();
        }
    }
}
