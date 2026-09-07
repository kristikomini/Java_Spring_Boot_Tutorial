package it.unicam.cs.enrollment.web.dto;

/**
 * What the API returns for a member of teaching staff.
 */
public class ProfessorResponse {

    private Long id;
    private String staffNumber;
    private String fullName;
    private String email;
    private String title;
    private String italianTitle;
    private String department;

    public ProfessorResponse() {
        // required by JSON-B
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStaffNumber() {
        return staffNumber;
    }

    public void setStaffNumber(String staffNumber) {
        this.staffNumber = staffNumber;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getItalianTitle() {
        return italianTitle;
    }

    public void setItalianTitle(String italianTitle) {
        this.italianTitle = italianTitle;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }
}
