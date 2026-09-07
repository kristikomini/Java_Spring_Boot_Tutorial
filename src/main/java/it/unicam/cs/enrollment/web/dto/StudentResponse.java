package it.unicam.cs.enrollment.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What the API returns for a student.
 *
 * <h2>A response DTO is a CONTRACT</h2>
 * Once a client depends on these field names, changing them is a breaking
 * change. That is precisely why it is a separate class from the entity: the
 * entity is free to evolve with the database, and this class changes only when
 * you consciously decide to change the API.
 *
 * <h2>Two shapes, one class</h2>
 * {@link #enrollments} is populated only by the "detail" endpoint and left
 * {@code null} by the list endpoint. JSON-B OMITS null fields by default, so the
 * list response simply has no {@code enrollments} key rather than
 * {@code "enrollments": null}. That keeps payloads small and lets one class
 * serve both shapes.
 *
 * <p>Notice what is NOT here: no {@code version}, no internal audit fields
 * beyond {@code createdAt}. Expose what clients need, not what you happen to
 * store.
 */
public class StudentResponse {

    private Long id;
    private String studentNumber;
    private String firstName;
    private String lastName;

    /**
     * A DERIVED field with no counterpart in the database. Computing it
     * server-side means every client renders names identically instead of each
     * one inventing its own concatenation.
     */
    private String fullName;

    private String email;
    private LocalDate dateOfBirth;
    private String status;
    private int enrollmentYear;

    /**
     * Boxed, and {@code null} on the list endpoint.
     *
     * <p>Both figures are computed by walking the student's enrollments, which
     * the list query deliberately does not load. A primitive {@code int} would
     * serialise as {@code 0} and claim the student has earned no credits - a
     * confidently wrong answer. {@code null} (omitted from the JSON) correctly
     * says "not included in this view".
     *
     * <p>Choosing a type that can express "unknown" is the point: primitives
     * cannot, so they quietly turn missing data into a plausible-looking value.
     */
    private Integer earnedCredits;
    private Double weightedAverage;

    private Instant createdAt;

    /** Present only on the detail endpoint. */
    private List<EnrollmentResponse> enrollments;

    public StudentResponse() {
        // required by JSON-B
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStudentNumber() {
        return studentNumber;
    }

    public void setStudentNumber(String studentNumber) {
        this.studentNumber = studentNumber;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getEnrollmentYear() {
        return enrollmentYear;
    }

    public void setEnrollmentYear(int enrollmentYear) {
        this.enrollmentYear = enrollmentYear;
    }

    public Integer getEarnedCredits() {
        return earnedCredits;
    }

    public void setEarnedCredits(Integer earnedCredits) {
        this.earnedCredits = earnedCredits;
    }

    public Double getWeightedAverage() {
        return weightedAverage;
    }

    public void setWeightedAverage(Double weightedAverage) {
        this.weightedAverage = weightedAverage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<EnrollmentResponse> getEnrollments() {
        return enrollments;
    }

    public void setEnrollments(List<EnrollmentResponse> enrollments) {
        this.enrollments = enrollments;
    }
}
