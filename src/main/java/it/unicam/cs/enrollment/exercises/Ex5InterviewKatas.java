package it.unicam.cs.enrollment.exercises;

import java.util.List;
import java.util.Map;

/**
 * EXERCISE 5 - The ten katas a junior interview actually asks
 * =============================================================================
 * Difficulty: individually easy, collectively the whole coding round.
 *
 * <p>Run the tests for this exercise with:
 * <pre>mvn test -Pexercises -Dtest=Ex5InterviewKatasTest</pre>
 *
 * <h2>Why these ten, and why here</h2>
 * The fieldbook's interview chapter lists fifteen problems worth rehearsing.
 * Ten of them are plain Java and live in this class. The remaining five are a
 * JPQL report (see {@link Ex6EnrollmentReport}), a Spring Boot CRUD endpoint, a
 * React form and a React fetch - the last three cannot be tested from this
 * repository, so they stay as instructions in the fieldbook rather than
 * pretending to be exercises here.
 *
 * <p>None of these is difficult. All of them are embarrassing to fumble in
 * front of someone, which is exactly why they get asked: they cost the
 * interviewer ninety seconds and they separate "has written code" from "has
 * read about code".
 *
 * <h2>How to practise them properly</h2>
 * <ol>
 *   <li><b>Say the approach out loud before you type.</b> The single most common
 *       way to fail a coding round is going silent. The interviewer cannot mark
 *       what they cannot hear.</li>
 *   <li><b>State the complexity when you finish.</b> Every Javadoc below names
 *       the expected time and space. If your solution is worse, that is a real
 *       answer too - say which one you chose and why.</li>
 *   <li><b>Name the edge cases.</b> Empty input, one element, {@code null},
 *       duplicates, and integer overflow. The tests check all of them, because
 *       an interviewer will.</li>
 * </ol>
 *
 * <h2>The rules of this class</h2>
 * Every method here is {@code static} and pure: same input, same output, no
 * fields, no clock, no database. That is deliberate - it is what makes them
 * trivial to test, and it is a small demonstration of the argument in the
 * fieldbook's design chapter about why untestable dependencies are a design
 * problem rather than a testing problem.
 *
 * <p>You may not use the obvious library shortcut where the Javadoc forbids it
 * ({@code StringBuilder.reverse()}, {@code Collections.sort}, ...). The point of
 * the kata is the mechanism, not the result.
 */
public final class Ex5InterviewKatas {

    private Ex5InterviewKatas() {
        throw new AssertionError("kata holder - do not instantiate");
    }

    // -------------------------------------------------------------------------
    // 1. Reverse a string
    // -------------------------------------------------------------------------

    /**
     * Returns {@code input} with its characters in the opposite order.
     *
     * <p><b>Not allowed:</b> {@code StringBuilder.reverse()}. Write the loop.
     *
     * <p><b>Complexity:</b> O(n) time. O(n) space, because strings are immutable
     * in Java and a new one has to be built - which is itself worth saying out
     * loud, since it is the reason {@code StringBuilder} exists at all.
     *
     * <p><b>Edge cases:</b> {@code null} in, {@code null} out. An empty string
     * reverses to an empty string. A single character reverses to itself.
     *
     * @param input the string to reverse; may be {@code null}
     * @return the reversed string, or {@code null} if {@code input} was null
     */
    public static String reverse(String input) {
        // TODO kata 1: build the reversed string with a loop.
        throw new UnsupportedOperationException("kata 1 not implemented yet");
    }

    // -------------------------------------------------------------------------
    // 2. Palindrome
    // -------------------------------------------------------------------------

    /**
     * Whether {@code input} reads the same forwards and backwards, ignoring case
     * and ignoring everything that is not a letter or a digit.
     *
     * <p>So {@code "A man, a plan, a canal: Panama"} is a palindrome, and so is
     * {@code ""}. Asking the interviewer whether punctuation counts, <em>before</em>
     * you write anything, is part of what is being marked here.
     *
     * <p><b>Complexity:</b> O(n) time, O(1) extra space if you compare with two
     * pointers moving inwards. Building a cleaned copy first is O(n) space and is
     * an acceptable answer as long as you know that is what you did.
     *
     * <p><b>Edge cases:</b> {@code null} is not a palindrome (return false).
     * A string of only punctuation is one, because nothing is left to compare.
     *
     * @param input the candidate; may be {@code null}
     * @return {@code true} when it is a palindrome under the rules above
     */
    public static boolean isPalindrome(String input) {
        // TODO kata 2: two pointers, skipping non-alphanumeric characters.
        throw new UnsupportedOperationException("kata 2 not implemented yet");
    }

    // -------------------------------------------------------------------------
    // 3. Duplicates
    // -------------------------------------------------------------------------

    /**
     * Every value that appears more than once in {@code values}, each reported
     * once, in the order in which the duplicate was first discovered.
     *
     * <p>The idiomatic answer is one pass with a {@code HashSet}, relying on
     * {@code add()} returning {@code false} when the element was already there.
     * The nested-loop answer is O(n&sup2;) and interviewers notice.
     *
     * <p><b>Complexity:</b> O(n) average time, O(n) space.
     *
     * <p><b>Edge cases:</b> {@code null} array returns an empty list, never null.
     * A value appearing three times is still reported once.
     *
     * @param values the array to scan; may be {@code null}
     * @return the duplicated values, first-discovery order, never {@code null}
     */
    public static List<Integer> findDuplicates(int[] values) {
        // TODO kata 3: one HashSet, and watch what add() returns.
        throw new UnsupportedOperationException("kata 3 not implemented yet");
    }

    // -------------------------------------------------------------------------
    // 4. First non-repeated character
    // -------------------------------------------------------------------------

    /**
     * The first character of {@code input} that occurs exactly once.
     *
     * <p>Two passes: count, then scan again for the first count of one. The
     * subtlety is that the counting map must preserve insertion order if you
     * want to answer from the map instead of rescanning the string - which is
     * what {@code LinkedHashMap} is for, and is a good thing to mention.
     *
     * <p><b>Complexity:</b> O(n) time, O(k) space where k is the alphabet size.
     *
     * <p><b>Edge cases:</b> {@code null} or empty input returns {@code null}.
     * A string where every character repeats returns {@code null}. Case matters:
     * {@code 'a'} and {@code 'A'} are different characters.
     *
     * @param input the string to scan; may be {@code null}
     * @return the first non-repeating character, or {@code null} if there is none
     */
    public static Character firstNonRepeated(String input) {
        // TODO kata 4: count first, then find the first count of exactly one.
        throw new UnsupportedOperationException("kata 4 not implemented yet");
    }

    // -------------------------------------------------------------------------
    // 5. Word frequencies
    // -------------------------------------------------------------------------

    /**
     * How many times each word appears in {@code text}, lower-cased, where a
     * word is any run of characters separated by whitespace with leading and
     * trailing punctuation stripped.
     *
     * <p>This is the kata that rewards knowing the modern {@code Map} API:
     * {@code merge(word, 1, Integer::sum)} or
     * {@code computeIfAbsent(word, k -> new int[1])} beat the
     * get-check-null-put dance that most candidates write.
     *
     * <p><b>Complexity:</b> O(n) time and O(distinct words) space.
     *
     * <p><b>Edge cases:</b> {@code null} or blank text returns an empty map.
     * Repeated whitespace must not produce empty-string keys.
     *
     * @param text the text to count; may be {@code null}
     * @return word to occurrence count, never {@code null}
     */
    public static Map<String, Integer> wordFrequencies(String text) {
        // TODO kata 5: Map.merge is the one to reach for.
        throw new UnsupportedOperationException("kata 5 not implemented yet");
    }

    // -------------------------------------------------------------------------
    // 6. Missing number
    // -------------------------------------------------------------------------

    /**
     * The one value missing from {@code values}, which otherwise contains each
     * of {@code 1..n} exactly once.
     *
     * <p>Two classic answers. The arithmetic one - expected sum
     * {@code n * (n + 1) / 2} minus the actual sum - is the one to say first,
     * <em>together with</em> the observation that the sum overflows {@code int}
     * for large n and therefore wants a {@code long}. The XOR answer never
     * overflows at all, which is why it is the better one and why mentioning it
     * is worth a mark.
     *
     * <p><b>Complexity:</b> O(n) time, O(1) space.
     *
     * <p><b>Edge cases:</b> {@code n} is {@code values.length + 1}. An empty
     * array means n is 1 and the answer is 1. A {@code null} array is not valid
     * input - throw {@link IllegalArgumentException}.
     *
     * @param values the values present, each in {@code 1..n}, one missing
     * @return the missing value
     * @throws IllegalArgumentException if {@code values} is {@code null}
     */
    public static int findMissingNumber(int[] values) {
        // TODO kata 6: sum-difference or XOR. Mind the overflow.
        throw new UnsupportedOperationException("kata 6 not implemented yet");
    }

    // -------------------------------------------------------------------------
    // 7. Merge two sorted arrays
    // -------------------------------------------------------------------------

    /**
     * One ascending array containing every element of {@code left} and
     * {@code right}, both of which are already sorted ascending.
     *
     * <p>Concatenating and sorting is O((n+m) log(n+m)) and misses the point.
     * The merge is two indices advancing independently, and it is the inner loop
     * of merge sort - which is a good thing to say, because it shows you know
     * where the pattern comes from.
     *
     * <p><b>Complexity:</b> O(n + m) time, O(n + m) space.
     *
     * <p><b>Edge cases:</b> either side {@code null} or empty. Duplicates across
     * the two arrays are all kept. The off-by-one at the end - draining whichever
     * side still has elements - is what this kata is really testing.
     *
     * @param left  an ascending array; may be {@code null}
     * @param right an ascending array; may be {@code null}
     * @return a new ascending array, never {@code null}
     */
    public static int[] mergeSorted(int[] left, int[] right) {
        // TODO kata 7: two indices, then drain the remainder.
        throw new UnsupportedOperationException("kata 7 not implemented yet");
    }

    // -------------------------------------------------------------------------
    // 8. Binary search
    // -------------------------------------------------------------------------

    /**
     * The index of {@code target} in the ascending array {@code sorted}, or
     * {@code -1} when it is not present.
     *
     * <p>Write it iteratively. The detail that separates candidates is the
     * midpoint: {@code (low + high) / 2} overflows for large arrays, and
     * {@code low + (high - low) / 2} does not. That bug lived in the JDK's own
     * binary search for nine years, which makes it a very fair question.
     *
     * <p><b>Complexity:</b> O(log n) time, O(1) space.
     *
     * <p><b>Edge cases:</b> {@code null} or empty array returns {@code -1}.
     * A single-element array. The target smaller than everything, or larger.
     * With duplicates, any matching index is acceptable.
     *
     * @param sorted an ascending array; may be {@code null}
     * @param target the value to find
     * @return an index of {@code target}, or {@code -1}
     */
    public static int binarySearch(int[] sorted, int target) {
        // TODO kata 8: halve the interval. Mind the midpoint overflow.
        throw new UnsupportedOperationException("kata 8 not implemented yet");
    }

    // -------------------------------------------------------------------------
    // 9. Second highest
    // -------------------------------------------------------------------------

    /**
     * The second-largest <em>distinct</em> value in {@code values}.
     *
     * <p>"Distinct" is the whole question, and asking about it before coding is
     * the point: in {@code [5, 5, 3]} the answer is 3, not 5. Sorting works and
     * is O(n log n); one pass with two variables is O(n) and is the answer being
     * looked for.
     *
     * <p><b>Complexity:</b> O(n) time, O(1) space.
     *
     * <p><b>Edge cases:</b> fewer than two distinct values - including
     * {@code null}, empty, and {@code [7, 7, 7]} - throw
     * {@link IllegalArgumentException} rather than inventing an answer.
     *
     * @param values the values to scan; may be {@code null}
     * @return the second-largest distinct value
     * @throws IllegalArgumentException when there are fewer than two distinct values
     */
    public static int secondHighest(int[] values) {
        // TODO kata 9: two running maxima, and skip equal values.
        throw new UnsupportedOperationException("kata 9 not implemented yet");
    }

    // -------------------------------------------------------------------------
    // 10. Balanced parentheses
    // -------------------------------------------------------------------------

    /**
     * Whether every bracket in {@code input} is closed by the matching kind, in
     * the right order. Handles {@code ()}, {@code []} and <code>{}</code>;
     * every other character is ignored.
     *
     * <p>This is the canonical "do you know what a stack is for" question. Push
     * on an opening bracket, pop and compare on a closing one, and the string is
     * balanced only if the stack is empty at the end. {@code ArrayDeque} is the
     * modern choice; the legacy {@code Stack} class is synchronised and is worth
     * knowing not to use.
     *
     * <p><b>Complexity:</b> O(n) time, O(n) space in the worst case.
     *
     * <p><b>Edge cases:</b> {@code null} and empty are both balanced. A closing
     * bracket with an empty stack is unbalanced - that is the case candidates
     * forget, and {@code ")("} is the test for it.
     *
     * @param input the string to check; may be {@code null}
     * @return {@code true} when every bracket matches
     */
    public static boolean isBalanced(String input) {
        // TODO kata 10: a stack, and remember to check it is empty at the end.
        throw new UnsupportedOperationException("kata 10 not implemented yet");
    }
}
