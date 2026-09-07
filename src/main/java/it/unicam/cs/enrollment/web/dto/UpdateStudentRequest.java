package it.unicam.cs.enrollment.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code PATCH /api/students/{id}}.
 *
 * <h2>PATCH semantics: why nothing here is {@code @NotNull}</h2>
 * <ul>
 *   <li><b>PUT</b> REPLACES the resource. The body must be complete, and any
 *       field left out is set to its default. PUT is idempotent.</li>
 *   <li><b>PATCH</b> applies a PARTIAL update. Only the fields present are
 *       touched.</li>
 * </ul>
 * So a missing field means "leave it alone", which is why every field is
 * optional and the service checks each for {@code null} before applying it.
 *
 * <p>The unsolved wrinkle, worth knowing about: with this design you cannot
 * distinguish "field absent" from "field explicitly set to null". If your API
 * needs to support clearing a field, the usual answers are JSON Merge Patch
 * (RFC 7386), JSON Patch (RFC 6902), or wrapping each field in an
 * {@code Optional}-like holder. All three add complexity, which is why most
 * teams live with the limitation until a use case forces the issue.
 */
public class UpdateStudentRequest {

    @Size(max = 80)
    private String firstName;

    @Size(max = 80)
    private String lastName;

    @Email
    @Size(max = 255)
    private String email;

    public UpdateStudentRequest() {
        // required by JSON-B
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
