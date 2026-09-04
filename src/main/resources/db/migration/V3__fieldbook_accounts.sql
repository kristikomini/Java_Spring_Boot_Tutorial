-- =============================================================================
-- V3  FIELDBOOK ACCOUNTS, PROGRESS AND NOTES
-- =============================================================================
-- A second bounded context in the same database. The enrollment tables model a
-- university registrar; these model the people reading the course about it.
--
-- They share a schema and share nothing else: no foreign key crosses between
-- the two groups, and neither set of tables is joined to the other anywhere in
-- the application. That is deliberate, and it is the thing to look at if you
-- ever want to split this into two deployables - a context that shares no keys
-- can be lifted out with a data copy, while one that joins across the boundary
-- cannot be moved at all without rewriting the queries first.
--
-- The `fieldbook_` prefix does the work a separate schema would do, without the
-- operational cost of a second schema to grant permissions on. On a bigger
-- system, use the schema.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- fieldbook_accounts
-- -----------------------------------------------------------------------------
-- One row per person reading the course.
--
-- password_hash holds a self-describing PBKDF2 string, never a password and
-- never anything reversible. See PasswordHasher for the format and for why the
-- iteration count is stored alongside the digest.
CREATE TABLE fieldbook_accounts (
    id             BIGINT       NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP(6) WITH TIME ZONE,
    version        BIGINT       NOT NULL,
    email          VARCHAR(255) NOT NULL,
    display_name   VARCHAR(60)  NOT NULL,
    password_hash  VARCHAR(200) NOT NULL,
    time_zone      VARCHAR(60),
    last_seen_at   TIMESTAMP(6) WITH TIME ZONE,
    best_streak    INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- The application lower-cases every address before it gets here, so this
    -- constraint means what a human expects. Without that normalisation the
    -- database would happily hold two accounts differing only in capitals.
    CONSTRAINT uk_fieldbook_accounts_email UNIQUE (email),
    CONSTRAINT ck_fieldbook_accounts_streak CHECK (best_streak >= 0)
);

-- -----------------------------------------------------------------------------
-- fieldbook_account_roles
-- -----------------------------------------------------------------------------
-- An @ElementCollection: a set of scalars owned by one account, with no
-- identity of its own. Note that the primary key is the pair, not a surrogate
-- id - which is what makes "grant the same role twice" impossible rather than
-- merely unlikely.
CREATE TABLE fieldbook_account_roles (
    account_id BIGINT      NOT NULL,
    role       VARCHAR(30) NOT NULL,
    PRIMARY KEY (account_id, role),
    CONSTRAINT fk_fb_roles_account FOREIGN KEY (account_id)
        REFERENCES fieldbook_accounts (id)
);

-- -----------------------------------------------------------------------------
-- fieldbook_study_days
-- -----------------------------------------------------------------------------
-- One row per calendar day on which the learner answered something. The streak
-- is computed from these rather than stored, so it cannot drift - see
-- LearnerAccount.currentStreak.
--
-- DATE, not TIMESTAMP: "which day was that, for you" is a question about a
-- local calendar. The zone it was resolved in lives on the account.
CREATE TABLE fieldbook_study_days (
    account_id BIGINT NOT NULL,
    study_day  DATE   NOT NULL,
    PRIMARY KEY (account_id, study_day),
    CONSTRAINT fk_fb_days_account FOREIGN KEY (account_id)
        REFERENCES fieldbook_accounts (id)
);

-- -----------------------------------------------------------------------------
-- fieldbook_sessions
-- -----------------------------------------------------------------------------
-- One row per logged-in browser.
--
-- token_hash is SHA-256 of the cookie value, hex encoded, so it is always
-- exactly 64 characters. The raw token is never stored: a stolen backup of this
-- table yields hashes, and a hash cannot be presented as a cookie.
CREATE TABLE fieldbook_sessions (
    id          BIGINT       NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE,
    version     BIGINT       NOT NULL,
    account_id  BIGINT       NOT NULL,
    token_hash  CHAR(64)     NOT NULL,
    expires_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_agent  VARCHAR(200),
    PRIMARY KEY (id),
    CONSTRAINT uk_fieldbook_sessions_token UNIQUE (token_hash),
    CONSTRAINT fk_fb_sessions_account FOREIGN KEY (account_id)
        REFERENCES fieldbook_accounts (id)
);

-- Every authenticated request looks a session up by account. Cheap, and pays
-- for "log me out everywhere" as well.
CREATE INDEX idx_fb_sessions_account ON fieldbook_sessions (account_id);

-- Serves the nightly sweep: DELETE ... WHERE expires_at < now.
CREATE INDEX idx_fb_sessions_expires ON fieldbook_sessions (expires_at);

-- -----------------------------------------------------------------------------
-- fieldbook_cards
-- -----------------------------------------------------------------------------
-- The Leitner box state of one question for one learner.
--
-- card_key is a hash of the QUESTION TEXT, produced by the browser, not a
-- position in a list. Numbering would mean that inserting a question silently
-- reassigns everyone history from that point on. See CardProgress.
CREATE TABLE fieldbook_cards (
    id          BIGINT       NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE,
    version     BIGINT       NOT NULL,
    account_id  BIGINT       NOT NULL,
    card_key    VARCHAR(80)  NOT NULL,
    chapter_id  VARCHAR(60),
    box         INTEGER      NOT NULL,
    times_seen  INTEGER      NOT NULL DEFAULT 0,
    last_result VARCHAR(10),
    due_at      TIMESTAMP(6) WITH TIME ZONE,
    -- The merge clock, deliberately separate from updated_at. updated_at is an
    -- audit column maintained by a JPA callback; synced_at is when the learner
    -- last ANSWERED, which is the value the offline merge compares. One column
    -- asked to mean two things eventually disagrees with itself.
    synced_at   TIMESTAMP(6) WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT uk_fieldbook_cards_account_key UNIQUE (account_id, card_key),
    CONSTRAINT fk_fb_cards_account FOREIGN KEY (account_id)
        REFERENCES fieldbook_accounts (id),
    -- The box range is enforced here as well as in Java. The application is one
    -- writer; a data fix applied by hand at midnight is another, and the
    -- database is the only thing both of them go through.
    CONSTRAINT ck_fieldbook_cards_box CHECK (box BETWEEN 1 AND 5),
    -- Enums are stored by NAME, so the database can check them. This is the
    -- constraint you lose the moment somebody switches to EnumType.ORDINAL.
    CONSTRAINT ck_fieldbook_cards_result
        CHECK (last_result IS NULL OR last_result IN ('RIGHT', 'WRONG'))
);

-- "What is due now" is the query the revision queue runs on every visit.
-- account_id first because it is the equality filter; due_at second because it
-- is the range. Range column last is the general rule for a composite index,
-- and getting it backwards makes the index useless for this query.
CREATE INDEX idx_fb_cards_account_due ON fieldbook_cards (account_id, due_at);

-- -----------------------------------------------------------------------------
-- fieldbook_chapters
-- -----------------------------------------------------------------------------
CREATE TABLE fieldbook_chapters (
    id          BIGINT       NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE,
    version     BIGINT       NOT NULL,
    account_id  BIGINT       NOT NULL,
    chapter_id  VARCHAR(60)  NOT NULL,
    read_at     TIMESTAMP(6) WITH TIME ZONE,
    best_score  INTEGER      NOT NULL DEFAULT 0,
    attempts    INTEGER      NOT NULL DEFAULT 0,
    passed_at   TIMESTAMP(6) WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT uk_fieldbook_chapters_account_chapter UNIQUE (account_id, chapter_id),
    CONSTRAINT fk_fb_chapters_account FOREIGN KEY (account_id)
        REFERENCES fieldbook_accounts (id),
    CONSTRAINT ck_fieldbook_chapters_score CHECK (best_score BETWEEN 0 AND 100),
    CONSTRAINT ck_fieldbook_chapters_attempts CHECK (attempts >= 0)
);

-- -----------------------------------------------------------------------------
-- fieldbook_notes
-- -----------------------------------------------------------------------------
-- sort_index is a DOUBLE rather than an INTEGER so a note can be dropped
-- between two others by averaging their indices - one row written per reorder
-- instead of renumbering the whole board. The cost is that averaging into the
-- same gap runs out of precision after roughly fifty insertions, at which point
-- a real implementation renumbers in the background.
CREATE TABLE fieldbook_notes (
    id          BIGINT           NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE,
    version     BIGINT           NOT NULL,
    account_id  BIGINT           NOT NULL,
    chapter_id  VARCHAR(60)      NOT NULL,
    body        TEXT             NOT NULL,
    colour      VARCHAR(12)      NOT NULL,
    pinned      BOOLEAN          NOT NULL DEFAULT FALSE,
    sort_index  DOUBLE PRECISION NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_fb_notes_account FOREIGN KEY (account_id)
        REFERENCES fieldbook_accounts (id)
);

CREATE INDEX idx_fb_notes_account ON fieldbook_notes (account_id);
CREATE INDEX idx_fb_notes_account_chapter ON fieldbook_notes (account_id, chapter_id);

-- =============================================================================
-- A note on what is NOT here
-- =============================================================================
-- No ON DELETE CASCADE anywhere. Deleting an account removes its rows in
-- AccountService, explicitly, in dependency order, inside one transaction.
--
-- Cascading deletes in the schema are convenient and are how people
-- accidentally delete a great deal of data: the DELETE that looked like it
-- touched one row touched four tables, and nothing in the statement said so.
-- Explicit is slower to write and never surprises the person reading the code
-- six months later.
-- =============================================================================
