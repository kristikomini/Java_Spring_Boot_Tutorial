package it.unicam.cs.enrollment.fieldbook.domain;

import it.unicam.cs.enrollment.domain.model.BaseEntity;
import it.unicam.cs.enrollment.domain.model.Email;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A person who reads the fieldbook. One row per human, and the anchor that
 * every other table in this package points at.
 *
 * <h2>Why this class is not in {@code domain.model} next to Student</h2>
 * Because a learner is not a student, even though most of the same people
 * would be both. {@code Student} belongs to the enrollment domain: it has a
 * matriculation number, a status the registrar controls, and enrollments that
 * carry grades. A {@code LearnerAccount} belongs to the fieldbook: it has a
 * password, a reading streak and some sticky notes.
 *
 * <p>Merging them would look like sensible reuse for about a week. Then the
 * registrar suspends a student and the fieldbook logs them out; then somebody
 * who is not a student wants to read the course and you discover that
 * "student number" is mandatory. The vocabulary for this in an interview is
 * BOUNDED CONTEXT: the same word - user, account, customer - means different
 * things to different parts of a business, and the mistake is assuming one
 * table can serve both meanings. Two contexts in one deployable is normal and
 * cheap. One table serving two meanings is neither.
 *
 * <p>What the two contexts share is a SHARED KERNEL: the small set of types
 * both agree on. Here that is exactly {@link BaseEntity} and {@link Email}.
 * Keeping the shared part deliberately tiny is the whole trick.
 *
 * <h2>What is stored, and what deliberately is not</h2>
 * No plaintext password appears in this class or its table - see
 * {@code fieldbook.security.PasswordHasher}. There is no email verification,
 * no password reset and no OAuth. Those are real features with real designs,
 * and their absence is written down in the fieldbook's security chapter rather
 * than quietly left out.
 */
@Entity
@Table(
        name = "fieldbook_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_fieldbook_accounts_email",
                columnNames = "email")
)
@NamedQuery(
        name = "LearnerAccount.findByEmail",
        query = "SELECT a FROM LearnerAccount a WHERE a.email.value = :email"
)
public class LearnerAccount extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Everybody who registers. */
    public static final String ROLE_LEARNER = "learner";

    /** Whoever is allowed to read aggregate statistics across accounts. */
    public static final String ROLE_AUTHOR = "author";

    /**
     * The natural key, and the thing typed into the login box.
     *
     * <p>{@link Email} normalises to lower case in its factory, which is what
     * makes the unique constraint above mean what a human expects it to mean.
     * Without normalisation "Mario@unicam.it" and "mario@unicam.it" are two
     * accounts, and the second one is a support ticket.
     */
    @Embedded
    private Email email;

    /**
     * What the page greets you with. Free text on purpose: it is a label, not
     * an identifier, so it is neither unique nor used for anything else.
     */
    @NotBlank
    @Size(max = 60)
    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    /**
     * The PBKDF2 output in its self-describing string form - never the password.
     * The format carries the algorithm, the cost and the salt alongside the
     * digest, so the parameters can be raised later without invalidating every
     * existing row. See {@code PasswordHasher} for the reasoning.
     *
     * <p>{@code length = 200} leaves headroom for a higher iteration count and
     * a longer digest. Sizing a hash column exactly to today's algorithm is a
     * migration you will have to write in two years.
     */
    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    /**
     * Roles, as a plain string set. Modelling them as a collection rather than
     * a single column costs nothing now and saves an awkward migration the
     * first time somebody needs two of them.
     *
     * <p>{@code FetchType.EAGER} here is a considered exception to the usual
     * "everything lazy" rule: roles are read on every authenticated request,
     * are at most a handful of short strings, and are needed after the
     * transaction that loaded the account has closed. Lazy would buy a
     * {@code LazyInitializationException}, not a saving.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "fieldbook_account_roles",
            joinColumns = @JoinColumn(name = "account_id",
                    foreignKey = @ForeignKey(name = "fk_fb_roles_account"))
    )
    @Column(name = "role", nullable = false, length = 30)
    private Set<String> roles = new HashSet<>();

    /**
     * Every calendar day on which this learner answered at least one question.
     *
     * <h3>Why a set of dates rather than an integer counter</h3>
     * A {@code streak} column would have to be updated by whoever noticed the
     * day had changed, which makes the number wrong for anyone who studies at
     * 23:59, wrong again across a timezone change, and unrecoverable once it
     * has drifted. Storing the raw facts and computing the streak on read is
     * marginally more work per request and cannot go stale. This is the event
     * sourcing argument in miniature: store the facts, derive the summary.
     *
     * <p>The cost is honest - one row per study day per learner, forever. At a
     * row a day that is 365 a year, which is nothing. If it ever were
     * something, the fix is to keep the last N days plus a frozen count of the
     * rest, and you would only know to do that from a measurement.
     *
     * <p>{@code LocalDate} rather than {@code Instant} is deliberate, and this
     * is the one place in the codebase where a timezone-free type is the
     * correct choice: "which day was that, for you" is a question about a local
     * calendar, not about a point on the UTC timeline. The zone itself is
     * stored per account below.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "fieldbook_study_days",
            joinColumns = @JoinColumn(name = "account_id",
                    foreignKey = @ForeignKey(name = "fk_fb_days_account"))
    )
    @Column(name = "study_day", nullable = false)
    private Set<LocalDate> studyDays = new LinkedHashSet<>();

    /**
     * The IANA zone the browser reported, for example {@code Europe/Rome}. Used
     * only to decide which calendar day "now" falls on. Stored rather than
     * inferred because a learner travelling for a week should not lose a streak
     * to a server that lives in UTC.
     */
    @Column(name = "time_zone", length = 60)
    private String timeZone;

    /** Last time this account authenticated. Purely informational. */
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    /**
     * The longest streak ever achieved. This one IS a stored counter, because
     * it is a high-water mark: it can only be computed from history that is
     * still present, so trimming {@code studyDays} later would silently lower
     * it. Facts you might one day discard need their summary kept.
     */
    @Column(name = "best_streak", nullable = false)
    private int bestStreak;

    /** Required by JPA. */
    protected LearnerAccount() {
        // required by JPA
    }

    private LearnerAccount(Email email, String displayName, String passwordHash, String timeZone) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.timeZone = timeZone;
        this.roles.add(ROLE_LEARNER);
    }

    /**
     * The only way to build one. A factory rather than a public constructor
     * because the name says what is happening, and because there is exactly one
     * valid shape for a brand new account: a learner, with no streak and no
     * history.
     *
     * @param passwordHash an ALREADY HASHED password. Taking the hash rather
     *                     than the password is deliberate: this class then has
     *                     no way to store plaintext even by accident, and the
     *                     hashing cost stays in the service, where it can be
     *                     configured and measured.
     */
    public static LearnerAccount register(Email email, String displayName,
                                          String passwordHash, String timeZone) {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        return new LearnerAccount(email, displayName.trim(), passwordHash, timeZone);
    }

    /**
     * Record that the learner did something today, and raise the high-water
     * mark if today extended the streak.
     *
     * <p>Returns {@code true} only when this was the first activity of the day,
     * which lets the caller skip an UPDATE on every single answer. Adding to a
     * {@code Set} is idempotent; the return value is what makes it cheap.
     */
    public boolean recordStudyDay(LocalDate day) {
        Objects.requireNonNull(day, "day must not be null");
        boolean added = studyDays.add(day);
        if (added) {
            int current = currentStreak(day);
            if (current > bestStreak) {
                bestStreak = current;
            }
        }
        return added;
    }

    /**
     * How many consecutive days ending at {@code today} contain study activity.
     *
     * <p>Yesterday counts as unbroken and today does not have to have happened
     * yet: a streak whose last day is yesterday is still alive until midnight.
     * Getting this wrong - resetting at 00:00 rather than at the end of the
     * following day - is the single most complained-about behaviour in every
     * app that has a streak, and it is this one line.
     */
    public int currentStreak(LocalDate today) {
        return streakOf(studyDays, today);
    }

    /**
     * The same rule, over any collection of days.
     *
     * <p>It is static and takes the days as an argument for a practical reason:
     * {@link #studyDays} is a LAZY collection, so calling the instance method on
     * an account that has left its persistence context throws
     * {@code LazyInitializationException}. That is not a hypothetical - it is
     * how this method came to be written. The authentication filter resolves
     * the account inside its own transaction, the transaction commits, and by
     * the time a resource method asks for the streak the collection can no
     * longer be loaded.
     *
     * <p>The two obvious fixes are both worse. Making the collection eager
     * loads every study day on every authenticated request and grows forever.
     * Keeping the whole request in one transaction means holding a database
     * connection while serialising JSON. The third option is this one: leave
     * the collection lazy, and let a caller who needs the days fetch exactly
     * them with a targeted query - see {@code LearnerAccountRepository.studyDaysFor}.
     *
     * <p>That is the general shape of the answer to lazy loading, and it is
     * worth recognising: the question is never "eager or lazy", it is "which
     * query does this use case need".
     */
    public static int streakOf(java.util.Collection<LocalDate> days, LocalDate today) {
        Objects.requireNonNull(days, "days must not be null");
        Objects.requireNonNull(today, "today must not be null");
        java.util.Set<LocalDate> set = (days instanceof java.util.Set)
                ? (java.util.Set<LocalDate>) days
                : new java.util.HashSet<>(days);
        LocalDate cursor = set.contains(today) ? today : today.minusDays(1);
        int n = 0;
        while (set.contains(cursor)) {
            n++;
            cursor = cursor.minusDays(1);
        }
        return n;
    }

    /** Sorted copy, for reporting. The field itself stays encapsulated. */
    public List<LocalDate> studyDaysSorted() {
        return new ArrayList<>(new TreeSet<>(studyDays));
    }

    public void touch(Instant now) {
        this.lastSeenAt = now;
    }

    public void changePasswordHash(String newHash) {
        this.passwordHash = Objects.requireNonNull(newHash, "newHash must not be null");
    }

    public void rename(String newDisplayName) {
        if (newDisplayName == null || newDisplayName.trim().isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        this.displayName = newDisplayName.trim();
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public Email getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Set<String> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public void grantRole(String role) {
        roles.add(role);
    }

    public Set<LocalDate> getStudyDays() {
        return Collections.unmodifiableSet(studyDays);
    }

    public String getTimeZone() {
        return timeZone;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public int getBestStreak() {
        return bestStreak;
    }
}
