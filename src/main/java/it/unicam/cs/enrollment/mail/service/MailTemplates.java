package it.unicam.cs.enrollment.mail.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a template name plus some values into a subject line and a body.
 *
 * <h2>Why the words are not in the Java source</h2>
 * Wording changes constantly, and almost never for a technical reason: the
 * registrar wants a friendlier greeting, legal wants a footer, someone spots a
 * typo. When the text lives in a string literal, each of those is a code
 * change, a review, a build and a deploy. When it lives in a file under
 * {@code src/main/resources/mail/templates}, it is a file edit - and, in a
 * system that loaded them from the database or a CMS, not a deploy at all.
 *
 * <p>It is also the seam that internationalisation needs. Adding Italian means
 * {@code enrollment-confirmed_it.txt} and a locale in the lookup; it does not
 * mean an {@code if} in the middle of the enrollment listener.
 *
 * <h2>The file format</h2>
 * A template looks like the email it produces - a subject header, a blank line,
 * then the body:
 * <pre>
 *   Subject: You are enrolled in ${courseCode}
 *
 *   Hello ${studentName},
 *
 *   You now have a seat in ${courseTitle}.
 * </pre>
 * That is RFC 5322's own shape, and it beats a properties file with
 * backslash-continued lines for anything a human has to read or edit.
 *
 * <h2>Substitution, and its one deliberate strictness</h2>
 * {@code ${name}} is replaced from the model. A placeholder with no value is an
 * ERROR, not an empty string. The alternative produces "Hello ," in somebody's
 * inbox - a bug that no test catches, that nobody reports, and that quietly
 * makes an institution look careless. Failing at render time means the
 * enrollment transaction rolls back and the log names the missing key.
 *
 * <h2>What this is not</h2>
 * It is not a template ENGINE. There are no loops, no conditionals and no
 * expressions, because every one of those turns a text file into code that
 * nobody tests. When a message genuinely needs a table of rows, reach for a
 * real engine (Qute, Freemarker, Thymeleaf) rather than growing this one.
 *
 * <p>Nor does it escape anything: these bodies are {@code text/plain}. The day
 * an HTML template appears here, every substituted value needs HTML-escaping
 * first, or a student who registers with the display name
 * {@code <script>...} has just been given a stored XSS in whatever web client
 * renders the message.
 */
@ApplicationScoped
public class MailTemplates {

    /** Template keys, so a typo is a compile error rather than a runtime one. */
    public static final String ENROLLMENT_CONFIRMED = "enrollment-confirmed";
    public static final String GRADE_PASSED = "grade-passed";
    public static final String GRADE_HONOURS = "grade-honours";
    public static final String GRADE_FAILED = "grade-failed";
    public static final String TEST_MESSAGE = "test-message";

    /**
     * The one template that belongs to the fieldbook rather than to the
     * registrar - and the reason the mail package is infrastructure shared by
     * both bounded contexts rather than part of either. A second context
     * needing to send mail is the moment that distinction stops being
     * theoretical.
     */
    public static final String PASSWORD_RESET = "password-reset";

    private static final String TEMPLATE_PATH = "/mail/templates/";
    private static final String SUBJECT_PREFIX = "Subject:";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_.]+)}");

    /**
     * Parsed templates, cached after first use.
     *
     * <p>{@code ConcurrentHashMap} rather than a plain map because this bean is
     * application-scoped and therefore shared by every request thread. Two
     * threads may parse the same template simultaneously on a cold start; they
     * produce equal results and one overwrites the other, which is harmless -
     * the alternative, a synchronised block on every render, buys nothing.
     */
    private final Map<String, ParsedTemplate> cache = new ConcurrentHashMap<>();

    /**
     * A rendered message, ready to be queued.
     *
     * <p>A record: it is a group of values with no behaviour and no identity,
     * which is exactly the case records exist for. The accessors are
     * {@code subject()} and {@code body()} rather than {@code getSubject()} -
     * the JavaBean convention does not apply, and is not missed.
     */
    public record RenderedMail(String subject, String body) {
    }

    /**
     * Render a template.
     *
     * @param key   the file name without extension, e.g. {@code grade-passed}
     * @param model the values for its placeholders
     * @throws IllegalArgumentException if the template does not exist, or the
     *                                  model is missing a placeholder it uses
     */
    public RenderedMail render(String key, Map<String, String> model) {
        ParsedTemplate template = cache.computeIfAbsent(key, MailTemplates::load);
        return new RenderedMail(
                substitute(template.subject(), model, key),
                substitute(template.body(), model, key));
    }

    /** Which templates exist, for the mailbox API and for a smoke test at startup. */
    public Set<String> knownTemplates() {
        Set<String> keys = new LinkedHashSet<>();
        Collections.addAll(keys,
                ENROLLMENT_CONFIRMED, GRADE_PASSED, GRADE_HONOURS, GRADE_FAILED,
                PASSWORD_RESET, TEST_MESSAGE);
        return Collections.unmodifiableSet(keys);
    }

    // ------------------------------------------------------------------
    // Loading and parsing
    // ------------------------------------------------------------------

    private static ParsedTemplate load(String key) {
        String resource = TEMPLATE_PATH + key + ".txt";

        // The CLASS's loader, not the thread context loader: in an application
        // server those can differ, and the one that can definitely see the
        // resources packaged beside this class is this one.
        try (InputStream in = MailTemplates.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No mail template at " + resource);
            }
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), resource);
        } catch (IOException e) {
            // Reading a file that is inside our own deployment cannot fail in
            // any way a caller could handle, so it is wrapped unchecked rather
            // than pushed into every signature above.
            throw new UncheckedIOException("Could not read mail template " + resource, e);
        }
    }

    private static ParsedTemplate parse(BufferedReader reader, String resource) throws IOException {
        String subjectLine = reader.readLine();
        if (subjectLine == null || !subjectLine.startsWith(SUBJECT_PREFIX)) {
            throw new IllegalArgumentException(
                    "Mail template " + resource + " must start with a '" + SUBJECT_PREFIX + "' line");
        }
        String subject = subjectLine.substring(SUBJECT_PREFIX.length()).trim();

        StringBuilder body = new StringBuilder();
        String line;
        boolean started = false;
        while ((line = reader.readLine()) != null) {
            // Skip the blank line(s) between the header and the body, but keep
            // every blank line inside the body itself - paragraph breaks are
            // most of what makes a plain-text email readable.
            if (!started && line.trim().isEmpty()) {
                continue;
            }
            started = true;
            body.append(line).append('\n');
        }

        if (subject.isEmpty() || body.length() == 0) {
            throw new IllegalArgumentException(
                    "Mail template " + resource + " has an empty subject or body");
        }
        return new ParsedTemplate(subject, body.toString().trim());
    }

    private static String substitute(String text, Map<String, String> model, String key) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            String name = matcher.group(1);
            String value = model.get(name);
            if (value == null) {
                throw new IllegalArgumentException(
                        "Mail template '" + key + "' uses ${" + name + "}, "
                                + "which the model does not provide. Known keys: " + model.keySet());
            }
            // quoteReplacement, because a value containing a $ or a backslash is
            // otherwise interpreted as a group reference by the regex machinery.
            // A student called "O'Brien" is fine; a course titled "C$ 101" would
            // have thrown. Rare inputs are still inputs.
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Internal: the two halves of a template file, after parsing. */
    private record ParsedTemplate(String subject, String body) {
    }
}
