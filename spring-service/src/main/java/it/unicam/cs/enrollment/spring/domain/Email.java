package it.unicam.cs.enrollment.spring.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/**
 * A value object, mapped with {@code @Embeddable} so it occupies a plain column
 * rather than a table of its own.
 *
 * <p>Note the imports: {@code jakarta.persistence}, {@code jakarta.validation} -
 * exactly the same annotations the Jakarta EE application uses. This is the most
 * useful thing to notice in the whole module, and the answer to the question
 * juniors ask most often about Spring. JPA and Bean Validation are
 * SPECIFICATIONS. Spring Boot does not have an ORM of its own; it ships
 * Hibernate, which is the same implementation WildFly ships, behind the same
 * standard interface. Your mapping knowledge transfers completely. What differs
 * is who starts Hibernate, who opens the transaction, and who hands you the
 * EntityManager - and those are the subject of the service and config classes,
 * not this one.
 *
 * <p>The class is immutable and the constructor is private, so an Email that
 * exists is an Email that passed {@link #of}. Fieldbook chapter 04 calls this
 * making illegal states unrepresentable; the practical payoff is that no
 * defensive re-check is needed anywhere downstream.
 */
@Embeddable
public class Email implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    @jakarta.validation.constraints.Email
    @Size(max = 255)
    @Column(name = "email", nullable = false, length = 255)
    private String value;

    /**
     * JPA requires a no-arg constructor to rebuild the object from a row. It is
     * {@code protected} rather than public so application code cannot reach it
     * and produce an Email with a null value - the one hole the factory method
     * would otherwise leave open.
     */
    protected Email() {
    }

    private Email(String value) {
        this.value = value;
    }

    public static Email of(String raw) {
        Objects.requireNonNull(raw, "email must not be null");
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        return new Email(trimmed);
    }

    public String getValue() {
        return value;
    }

    /** The part after the at-sign. Useful for rules like "must be a unicam.it address". */
    public String domain() {
        int at = value.indexOf('@');
        return at < 0 ? "" : value.substring(at + 1);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Email)) {
            return false;
        }
        return Objects.equals(value, ((Email) other).value);
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
