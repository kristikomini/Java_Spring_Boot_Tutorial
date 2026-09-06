package it.unicam.cs.enrollment.fieldbook.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/**
 * The handle a learner signs in with.
 *
 * <h2>Why the login name is not the email address any more</h2>
 * An email address is a way of REACHING somebody. A username is a way of
 * NAMING them. Using one for both looks economical and quietly couples two
 * things that change for different reasons:
 *
 * <ul>
 *   <li>An address changes - people leave a university, a company, a provider.
 *       If it is also the login, changing it changes who you are, and every
 *       row that referenced you by address is now wrong.</li>
 *   <li>An address is semi-public. It appears in mailing lists, in CC fields
 *       and in breach dumps, so using it as the login hands an attacker half of
 *       every credential pair for free.</li>
 *   <li>An address is not always available. Password reset needs one; signing
 *       in does not, and tying the two together means an account cannot exist
 *       before its address is verified.</li>
 * </ul>
 *
 * <p>So the two are separate fields with separate jobs: {@code Username}
 * identifies, {@link it.unicam.cs.enrollment.domain.model.Email} delivers. The
 * password reset flow is the one place both are needed at once, and it is
 * exactly the place the distinction pays for itself - see
 * {@link PasswordResetToken}.
 *
 * <h2>The character rule, and what relaxing it costs</h2>
 * One to thirty characters, trimmed and lower-cased. Beyond that the rule is
 * deliberately permissive: anything a person can type is a handle, including
 * spaces, accents and emoji. Only two things are still refused, and both are
 * refused because they break identity rather than because they are untidy:
 *
 * <ul>
 *   <li><b>Blank.</b> An account nobody can name is an account nobody can sign
 *       in to.</li>
 *   <li><b>Control characters.</b> They are invisible. Two handles that render
 *       identically but differ by a zero-width joiner are two accounts and one
 *       support ticket, and no amount of care at the login box can tell them
 *       apart. This is the one class of character where rejecting is kinder
 *       than accepting.</li>
 * </ul>
 *
 * <p>Normalisation still happens, and it is doing the real work. Trimming and
 * case folding are what make the unique constraint mean what a human expects:
 * without them {@code Mario}, {@code mario} and {@code mario } are three
 * accounts. That was always the load-bearing half of the old rule; the regex
 * was the part that merely tidied.
 *
 * <p><b>What the narrow rule used to buy, and no longer does.</b> It excluded
 * homographs - {@code раypal} with Cyrillic characters and {@code paypal} look
 * identical in every font and are different strings - and it excluded leading
 * and trailing punctuation, so {@code .mario} could not shadow {@code mario}.
 * Both are now possible. That is an acceptable trade here, where a handle names
 * a learner's own study record and impersonating one buys an attacker nothing;
 * it would not be acceptable on a system where a username is a public identity
 * others act on. The real fix at that point is Unicode NFKC plus a
 * confusable-character mapping, which is a library and a design, not a regex -
 * see {@code MAX_LENGTH} for the one limit that is not negotiable.
 *
 * <p>An {@code @Embeddable}, like {@code Email}, so the value lives as one
 * column in the owning table with no join - see that class for the longer note
 * on value objects.
 */
@Embeddable
public class Username implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The shortest handle accepted: one character.
     *
     * <p>It was three. Short handles do collide more often, but a collision is
     * reported honestly by the unique constraint and the person picks again -
     * which is a worse outcome than a rule only in the sense that it costs one
     * more keystroke, and a better one in that it is the person's choice.
     */
    public static final int MIN_LENGTH = 1;

    /**
     * The longest. Also the column width, deliberately the same number.
     *
     * <p>This is the one bound that is not a matter of taste: the column is
     * {@code VARCHAR(30)}, so a longer handle is not a rejected handle, it is a
     * failed INSERT deep inside a transaction with a message about a database
     * constraint. Raising it means a migration, not an edit here.
     */
    public static final int MAX_LENGTH = 30;

    @NotBlank
    @Size(min = MIN_LENGTH, max = MAX_LENGTH)
    @Column(name = "username", nullable = false, length = MAX_LENGTH)
    private String value;

    /** Required by JPA. */
    protected Username() {
        // required by JPA
    }

    private Username(String value) {
        this.value = value;
    }

    /**
     * The only way to build one, and the single place the rule above is
     * enforced.
     *
     * @throws IllegalArgumentException if the handle is blank, longer than
     *         {@link #MAX_LENGTH}, or contains control characters.
     *         Deliberately unchecked and deliberately vague about which it was:
     *         the caller in {@code AccountService} translates it into the one
     *         sentence a person can act on, and duplicating that wording here
     *         would give two places to change it.
     */
    public static Username of(String raw) {
        Objects.requireNonNull(raw, "username must not be null");
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (normalised.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("username is longer than " + MAX_LENGTH);
        }
        if (hasControlCharacters(normalised)) {
            throw new IllegalArgumentException("username contains control characters");
        }
        return new Username(normalised);
    }

    /**
     * Whether {@code raw} would be accepted, without building anything.
     *
     * <p>Exists for the migration path in {@code AccountService.suggestFrom},
     * which has to try several candidates and cannot use exceptions for
     * control flow without turning a loop into something unreadable.
     */
    public static boolean isValid(String raw) {
        if (raw == null) {
            return false;
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        return !normalised.isEmpty()
                && normalised.length() <= MAX_LENGTH
                && !hasControlCharacters(normalised);
    }

    /**
     * True if the handle contains anything invisible.
     *
     * <p>{@link Character#isISOControl} catches the C0 and C1 ranges, which is
     * the tab, the newline and the escape sequences a terminal would act on.
     * The {@code FORMAT} category is the subtler half: a zero-width joiner or a
     * right-to-left override renders as nothing at all, so two handles that
     * look identical on screen are different strings in the database. Refusing
     * both is the whole of what is left of the old character rule, and it is
     * the part that was load-bearing.
     */
    private static boolean hasControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) {
                return true;
            }
        }
        return false;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Username)) {
            return false;
        }
        return Objects.equals(value, ((Username) other).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
