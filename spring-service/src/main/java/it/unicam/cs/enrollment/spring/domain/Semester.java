package it.unicam.cs.enrollment.spring.domain;

/**
 * A carbon copy of {@code it.unicam.cs.enrollment.domain.model.Semester}, and it
 * has to be: the column is mapped {@code @Enumerated(EnumType.STRING)}, so these
 * constant NAMES are the values physically stored in courses.semester. Rename
 * one here and this application stops being able to read rows the other one
 * wrote.
 *
 * <p>That is the whole argument for STRING over ORDINAL. With ORDINAL the
 * database would hold 0 and 1, and reordering the constants - a change that
 * looks purely cosmetic in review - would silently reinterpret every existing
 * row.
 *
 * <p>Note also what a Java enum is allowed to be, which surprises people coming
 * from C: a full class with fields, a constructor and behaviour. FALL and SPRING
 * are the only two instances that will ever exist, and the compiler guarantees
 * it.
 */
public enum Semester {

    FALL("First semester", 1),
    SPRING("Second semester", 2);

    private final String displayName;
    private final int ordinalNumber;

    Semester(String displayName, int ordinalNumber) {
        this.displayName = displayName;
        this.ordinalNumber = ordinalNumber;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getOrdinalNumber() {
        return ordinalNumber;
    }

    public Semester next() {
        return this == FALL ? SPRING : FALL;
    }
}
