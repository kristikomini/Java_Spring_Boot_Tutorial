package it.unicam.cs.enrollment.mail.domain;

/**
 * The lifecycle of one queued email.
 *
 * <h2>Why a message has a state at all</h2>
 * The naive way to send mail is {@code transport.send(message)} at the moment
 * the interesting thing happens. It works right up until the SMTP server is
 * down for ninety seconds, and then the confirmation is simply gone: nothing
 * recorded that it was owed, so nothing can retry it.
 *
 * <p>Writing the message to a table first turns "send an email" into a piece of
 * state that survives a crash, and the column below is what tells the dispatcher
 * which rows still owe someone an email. This is the TRANSACTIONAL OUTBOX
 * pattern, and the state machine is the whole of it:
 *
 * <pre>
 *   PENDING ──sent ok──────────────&gt; SENT
 *      │  ▲
 *      │  └──transient failure, attempts left (nextAttemptAt pushed out)
 *      │
 *      ├──permanent failure, or attempts exhausted──&gt; DEAD
 *      │
 *      └──a human cancelled it──&gt; CANCELLED
 *
 *   PENDING ──claimed by the dispatcher──&gt; SENDING ──&gt; SENT | PENDING | DEAD
 * </pre>
 *
 * <h2>{@code SENDING} exists for one specific reason</h2>
 * Handing a message to an SMTP server takes network time, and network time must
 * not happen inside a database transaction (see {@code MailDispatcher}). So the
 * row is marked {@code SENDING} and committed BEFORE the socket is opened. If
 * the JVM is killed mid-send, the row is left in {@code SENDING} rather than
 * silently re-queued - the recovery sweep picks it up after a timeout and
 * decides, visibly, to try again.
 *
 * <p>That is also the honest description of what this system guarantees:
 * AT-LEAST-ONCE delivery. A crash in the window between "the server accepted
 * the message" and "we recorded that it did" produces a duplicate email. The
 * alternative, at-most-once, loses mail instead. For a confirmation email a
 * rare duplicate is much cheaper than a silent loss, and every real mail
 * pipeline makes the same trade.
 */
public enum MailStatus {

    /** Queued and owed to someone. The dispatcher looks for exactly these. */
    PENDING,

    /**
     * Claimed by a dispatcher and currently being handed to the transport.
     * A row that has sat here longer than {@code MailConfig.stuckAfter()} is
     * assumed to belong to a process that died, and is returned to PENDING.
     */
    SENDING,

    /** The transport accepted it. Terminal, and eventually purged. */
    SENT,

    /**
     * Given up on: either a permanent failure (there is no such mailbox) or the
     * retry budget ran out. Terminal, but deliberately NOT deleted - a dead
     * letter is evidence, and someone can retry it by hand from the mailbox API.
     */
    DEAD,

    /** A human stopped it before it went out. Terminal. */
    CANCELLED;

    /** True when no dispatcher will ever touch this row again. */
    public boolean isTerminal() {
        return this == SENT || this == DEAD || this == CANCELLED;
    }
}
