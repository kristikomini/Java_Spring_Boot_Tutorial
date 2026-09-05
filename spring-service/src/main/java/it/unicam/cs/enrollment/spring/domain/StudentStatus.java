package it.unicam.cs.enrollment.spring.domain;

/**
 * Stored as a string in students.status. See the note on {@link Semester} about
 * why the names matter.
 *
 * <p>{@link #canEnroll()} is a one-line example of the argument fieldbook
 * chapter 14 makes at length: the rule about who may enroll lives on the type
 * that knows the answer, not in an {@code if} inside a service. There is exactly
 * one place to change it, and it cannot be forgotten at a second call site.
 */
public enum StudentStatus {

    ACTIVE,
    SUSPENDED,
    GRADUATED,
    WITHDRAWN;

    public boolean canEnroll() {
        return this == ACTIVE;
    }
}
