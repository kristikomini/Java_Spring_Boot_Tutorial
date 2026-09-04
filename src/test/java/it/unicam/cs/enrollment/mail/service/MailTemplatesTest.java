package it.unicam.cs.enrollment.mail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Template rendering, including the case that matters most: a template that
 * asks for something the caller did not provide.
 */
@DisplayName("MailTemplates")
class MailTemplatesTest {

    private MailTemplates templates;

    @BeforeEach
    void setUp() {
        templates = new MailTemplates();
    }

    private static Map<String, String> enrollmentModel() {
        Map<String, String> model = new LinkedHashMap<>();
        model.put("studentName", "Mario Rossi");
        model.put("studentNumber", "123456");
        model.put("courseCode", "CS101");
        model.put("courseTitle", "Programming Fundamentals");
        model.put("enrolledOn", "1 March 2026");
        return model;
    }

    @Test
    @DisplayName("splits the Subject: header from the body")
    void parsesTheHeader() {
        MailTemplates.RenderedMail mail =
                templates.render(MailTemplates.ENROLLMENT_CONFIRMED, enrollmentModel());

        assertThat(mail.subject()).isEqualTo("You are enrolled in CS101");
        assertThat(mail.body()).startsWith("Dear Mario Rossi,");
        assertThat(mail.body()).doesNotContain("Subject:");
    }

    @Test
    @DisplayName("substitutes every placeholder, in the subject and the body")
    void substitutes() {
        MailTemplates.RenderedMail mail =
                templates.render(MailTemplates.ENROLLMENT_CONFIRMED, enrollmentModel());

        assertThat(mail.body())
                .contains("CS101 - Programming Fundamentals")
                .contains("123456")
                .contains("1 March 2026");

        // The most important assertion in the class: nothing unsubstituted got
        // through. A body containing a literal ${...} is a bug that reaches a
        // real person's inbox looking exactly like carelessness.
        assertThat(mail.body()).doesNotContain("${");
        assertThat(mail.subject()).doesNotContain("${");
    }

    @Test
    @DisplayName("keeps the blank lines that make plain text readable")
    void keepsParagraphs() {
        MailTemplates.RenderedMail mail =
                templates.render(MailTemplates.ENROLLMENT_CONFIRMED, enrollmentModel());

        assertThat(mail.body()).contains("\n\n");
        assertThat(mail.body()).doesNotStartWith("\n");
    }

    @Test
    @DisplayName("refuses to render with a value missing, rather than printing an empty one")
    void missingValueIsAnError() {
        Map<String, String> incomplete = new HashMap<>(enrollmentModel());
        incomplete.remove("courseTitle");

        assertThatThrownBy(() -> templates.render(MailTemplates.ENROLLMENT_CONFIRMED, incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("courseTitle")
                // The message lists what WAS supplied, because "which key did I
                // misspell" is the only question anyone asks at this point.
                .hasMessageContaining("studentName");
    }

    @Test
    @DisplayName("fails clearly when the template does not exist")
    void unknownTemplate() {
        assertThatThrownBy(() -> templates.render("no-such-template", enrollmentModel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/mail/templates/no-such-template.txt");
    }

    @Test
    @DisplayName("handles a value containing a dollar sign or a backslash")
    void escapesReplacementMetacharacters() {
        Map<String, String> model = enrollmentModel();
        model.put("courseTitle", "Cost models: $ and \\ in practice");

        MailTemplates.RenderedMail mail =
                templates.render(MailTemplates.ENROLLMENT_CONFIRMED, model);

        // Without Matcher.quoteReplacement this throws, or silently eats
        // characters: the regex machinery reads $ and \ in the REPLACEMENT as
        // group references. Rare input is still input.
        assertThat(mail.body()).contains("Cost models: $ and \\ in practice");
    }

    @Test
    @DisplayName("renders every template the application declares")
    void everyDeclaredTemplateLoads() {
        Map<String, String> everything = new LinkedHashMap<>(enrollmentModel());
        everything.put("grade", "30");
        everything.put("recordedOn", "1 March 2026");
        everything.put("sentBy", "Kristi");
        everything.put("note", "checking the relay");
        everything.put("transport", "log only (test)");

        // A cheap guard against the commonest template bug there is: a file
        // renamed, a key added to the constant list but not to the resources
        // folder, or a placeholder introduced that no caller supplies. All three
        // would deploy happily and fail at the moment a student enrols.
        for (String key : templates.knownTemplates()) {
            MailTemplates.RenderedMail mail = templates.render(key, everything);
            assertThat(mail.subject()).as("subject of %s", key).isNotBlank();
            assertThat(mail.body()).as("body of %s", key).isNotBlank();
            assertThat(mail.body()).as("body of %s", key).doesNotContain("${");
        }
    }
}
