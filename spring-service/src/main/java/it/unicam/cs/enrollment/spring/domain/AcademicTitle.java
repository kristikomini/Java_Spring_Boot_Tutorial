package it.unicam.cs.enrollment.spring.domain;

/** Stored as a string in professors.title. See the note on {@link Semester}. */
public enum AcademicTitle {

    ASSISTANT_PROFESSOR("Ricercatore"),
    ASSOCIATE_PROFESSOR("Professore Associato"),
    FULL_PROFESSOR("Professore Ordinario");

    private final String italianTitle;

    AcademicTitle(String italianTitle) {
        this.italianTitle = italianTitle;
    }

    public String getItalianTitle() {
        return italianTitle;
    }
}
