package it.unicam.cs.enrollment.mail.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "Send me one message so I can see whether any of this works."
 *
 * <h2>Why the endpoint behind this is the most dangerous one in the API</h2>
 * It takes an arbitrary address and a piece of arbitrary text and sends it from
 * a university's mail server. That is an open relay wearing a REST API, and it
 * is exactly the thing spammers search for. Three things keep it defensible,
 * and all three are needed:
 * <ul>
 *   <li>it is behind {@code @Authenticated}, so an anonymous caller cannot
 *       reach it at all;</li>
 *   <li>the BODY is not caller-supplied - the {@code note} below is one short
 *       field dropped into a fixed template, so nobody can compose a convincing
 *       phishing message through it;</li>
 *   <li>the note is length-limited, because "arbitrary text with a limit of
 *       200 characters" is a much smaller problem than "arbitrary text".</li>
 * </ul>
 *
 * <p>The general lesson is worth more than the endpoint: any feature that sends
 * attacker-influenced content from your domain is a feature you design
 * defensively, or do not build.
 */
public class SendTestMailRequest {

    @NotBlank(message = "A recipient address is required")
    @jakarta.validation.constraints.Email(message = "That is not a valid email address")
    @Size(max = 255)
    private String recipient;

    @Size(max = 200, message = "Keep the note under 200 characters")
    private String note;

    public SendTestMailRequest() {
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    /** A short line the sender will recognise in their inbox. Optional. */
    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
