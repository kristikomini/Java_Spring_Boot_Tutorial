-- =============================================================================
-- V4  THE MAIL OUTBOX
-- =============================================================================
-- One table, holding every email this application has promised to send.
--
-- The pattern is the TRANSACTIONAL OUTBOX: business code writes a row here in
-- the same transaction as the fact that caused it, and a separate process
-- delivers it afterwards. What that buys is an invariant you cannot get any
-- other way - there is no state of the world in which an enrollment exists and
-- the promise of its confirmation does not, or vice versa. A transaction can
-- roll back; an email that has left the building cannot.
--
-- Read this file alongside OutboxMessage.java. Every column below exists
-- because some failure mode needs it, and the entity's javadoc says which.
-- =============================================================================

CREATE TABLE mail_outbox (
    id              BIGINT       NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE,
    version         BIGINT       NOT NULL,

    -- WHO AND WHAT --------------------------------------------------------
    -- recipient is the Email value object embedded, with its column renamed
    -- at the point of embedding (@AttributeOverride). One address per row:
    -- this is a notification system, not a mailing-list engine, and "send the
    -- same message to 400 people" is a different problem with a different
    -- table shape.
    recipient       VARCHAR(255) NOT NULL,
    recipient_name  VARCHAR(120),
    subject         VARCHAR(255) NOT NULL,

    -- TEXT, not VARCHAR(n). The body is rendered once, at the moment of the
    -- fact, and stored - so the row is a faithful record of what was promised
    -- rather than of what a template file happens to say today.
    body            TEXT         NOT NULL,

    -- Which template produced it. Reporting only: nothing routes on this.
    template_key    VARCHAR(60),

    -- THE IDEMPOTENCY KEY -------------------------------------------------
    -- 'enrollment-confirmed:4711'. The UNIQUE constraint below is what
    -- actually makes double-queuing impossible; the check in MailService is a
    -- convenience that produces a friendly answer in the common case.
    --
    -- NULL is allowed and is exempt from UNIQUE in SQL, so messages that have
    -- no natural "once" key - a hand-sent test, a resit result - coexist
    -- happily with the ones that do.
    dedupe_key      VARCHAR(120),

    -- SCHEDULING STATE ----------------------------------------------------
    -- Stored as text, never as an ordinal: inserting a constant in the middle
    -- of the Java enum would otherwise reinterpret every existing row.
    status          VARCHAR(20)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,

    -- The DUE TIME rather than a delay, so the dispatcher's query is a plain
    -- comparison against the clock and an index can serve it.
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    -- When a dispatcher took ownership. A row still holding a claim long after
    -- this is one whose dispatcher died mid-send, and the recovery sweep
    -- re-queues it. Without this column, an unplanned restart silently loses
    -- whatever was in flight.
    claimed_at      TIMESTAMP(6) WITH TIME ZONE,
    sent_at         TIMESTAMP(6) WITH TIME ZONE,

    -- Why the last attempt failed. On the row, not only in the log, so the
    -- mailbox API can show it to someone who has no shell access to a server.
    last_error      VARCHAR(500),

    -- The request that caused this message, copied from the MDC. Turns "search
    -- the logs for that afternoon" into one grep.
    correlation_id  VARCHAR(60),

    PRIMARY KEY (id),
    CONSTRAINT uk_mail_outbox_dedupe_key UNIQUE (dedupe_key),
    CONSTRAINT ck_mail_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_mail_outbox_status CHECK (
        status IN ('PENDING', 'SENDING', 'SENT', 'DEAD', 'CANCELLED')
    )
);

-- -----------------------------------------------------------------------------
-- The dispatcher's hot query
-- -----------------------------------------------------------------------------
--   WHERE status = 'PENDING' AND next_attempt_at <= now ORDER BY next_attempt_at
--
-- Column order in a composite index is not cosmetic: the index can only be used
-- for a range scan on the SECOND column once the FIRST is pinned to a value.
-- (status, next_attempt_at) serves this query; (next_attempt_at, status) would
-- force a scan of every message ever sent.
--
-- The shape also survives success. A year in, this table is 99.9% SENT rows,
-- and PENDING is a handful - so the index lookup stays proportional to the work
-- outstanding rather than to the history.
CREATE INDEX idx_mail_outbox_due ON mail_outbox (status, next_attempt_at);

-- "What have we sent to this person?" - the first question support asks, and
-- the reason not to make them wait for a sequential scan.
CREATE INDEX idx_mail_outbox_recipient ON mail_outbox (recipient);

-- =============================================================================
-- What is NOT here
-- =============================================================================
-- No foreign key to enrollments, students or courses, even though every row is
-- caused by one of them. That is deliberate: an outbox row is a record of a
-- PAST FACT, and a past fact must not become undeletable - or worse, cascade
-- away - because of what happens to the entity that caused it. Deleting a
-- student should not rewrite the history of what they were sent.
--
-- The link is kept as data instead: the dedupe key carries the id it came from,
-- and correlation_id ties the row back to the request. Both are enough to
-- investigate, and neither couples two lifecycles that have no reason to be
-- coupled.
--
-- No attachments table, no HTML body, no per-recipient tracking pixels. Each of
-- those is a real feature of a real mail system and each would be its own
-- chapter; the point of this one is the delivery guarantee, and the guarantee is
-- what the columns above are for.
-- =============================================================================
