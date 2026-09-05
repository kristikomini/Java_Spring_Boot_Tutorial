package it.unicam.cs.enrollment.fieldbook.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

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
 * <h2>The character rule, and why it is this narrow</h2>
 * Lower case letters, digits, dot, underscore and hyphen; three to thirty
 * characters; must start and end with a letter or a digit. That excludes a
 * great many perfectly reasonable names, and the narrowness is the point:
 *
 * <ul>
 *   <li><b>Case folding.</b> Normalising to lower case is what makes the unique
 *       constraint mean what a human expects. Without it {@code Mario} and
 *       {@code mario} are two accounts and the second one is a support ticket -
 *       the same argument {@code Email} makes, for the same reason.</li>
 *   <li><b>Homograph confusion.</b> Allowing the whole of Unicode means
 *       {@code раypal} (with a Cyrillic а) and {@code paypal} look identical in
 *       every font and are different strings. ASCII sidesteps an impersonation
 *       problem that has no cheap general fix.</li>
 *   <li><b>Leading and trailing punctuation.</b> {@code .mario} and
 *       {@code mario.} are invisible variations on a name somebody already
 *       has.</li>
 * </ul>
 *
 * <p>The honest cost is that this is an English-alphabet rule on a course
 * written partly in Italian, and a system that genuinely needed international
 * handles would normalise with Unicode NFKC and a confusable-character mapping
 * instead. That is a real design with a real library behind it; pretending a
 * regex covers it would be worse than saying so.
 *
 * <p>An {@code @Embeddable}, like {@code Email}, so the value lives as one
 * column in the owning table with no join - see that class for the longer note
 * on value objects.
 */
@Embeddable
public class Username implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The shortest handle accepted. Below this, collisions are the norm. */
    public static final int MIN_LENGTH = 3;

    /** The longest. Also the column width, deliberately the same number. */
    public static final int MAX_LENGTH = 30;

    /**
     * Applied AFTER normalisation, so it only ever sees lower case.
     *
     * <p>Written as "one alphanumeric, then up to {@code MAX-2} of the wider
     * set, then one alphanumeric" rather than as a lookahead. Lookaheads read
     * as clever and are how a validation regex ends up meaning something
     * subtly different from its comment.
     */
    private static final Pattern SHAPE =
            Pattern.compile("^[a-z0-9][a-z0-9._-]{" + (MIN_LENGTH - 2) + "," + (MAX_LENGTH - 2) + "}[a-z0-9]$");

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
     * @throws IllegalArgumentException if the handle is empty or malformed.
     *         Deliberately unchecked and deliberately vague about which of the
     *         two it was: the caller in {@code AccountService} translates it
     *         into the one sentence a person can act on, and duplicating that
     *         wording here would give two places to change it.
     */
    public static Username of(String raw) {
        Objects.requireNonNull(raw, "username must not be null");
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (!SHAPE.matcher(normalised).matches()) {
            throw new IllegalArgumentException("username is not a valid handle: " + normalised);
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
        return !normalised.isEmpty() && SHAPE.matcher(normalised).matches();
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
