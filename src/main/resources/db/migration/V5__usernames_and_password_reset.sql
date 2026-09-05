-- =============================================================================
-- V5  LOGIN BY USERNAME, AND PASSWORD RESET
-- =============================================================================
-- Two changes that arrived together, because the second one is only possible
-- once the first has happened.
--
-- Before this migration a fieldbook account had exactly one name: its email
-- address, used both to identify it at the login box and to reach the person
-- behind it. That is one column doing two jobs, and the seam shows the moment
-- somebody forgets their password: the reset link has to be sent somewhere the
-- login form does not ask about, and there is nowhere for it to go that is not
-- also the credential being reset.
--
-- So the login name moves into its own column, and the address goes back to
-- being an address.
--
-- Read this file alongside Username.java and PasswordResetToken.java.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. fieldbook_accounts.username
-- -----------------------------------------------------------------------------
-- Added in three statements rather than one, and the order is the whole point.
-- A single
--
--     ALTER TABLE fieldbook_accounts ADD COLUMN username VARCHAR(30) NOT NULL
--
-- fails outright on any table that already has a row, because there is no
-- value to put in the existing ones and NOT NULL says so. The pattern for
-- adding a mandatory column to a populated table is always these three steps:
-- add it nullable, backfill it, then tighten the constraint. It is worth
-- recognising because it is the shape of nearly every schema change that
-- reaches production - the version that works on an empty developer database
-- and fails on the real one is the version written as a single statement.

ALTER TABLE fieldbook_accounts ADD COLUMN username VARCHAR(30);


-- -----------------------------------------------------------------------------
-- 2. Backfill
-- -----------------------------------------------------------------------------
-- Every existing account gets a handle derived from the local part of its
-- address: mario.rossi@studenti.unicam.it becomes mario.rossi.
--
-- Three things have to happen to that string, and each of them corresponds to a
-- rule in Username.of - which is the uncomfortable part of a backfill written
-- in SQL. The domain rule now exists in two languages, and they have to agree.
-- The alternative is a data migration written in Java that loads every account
-- and calls the real factory, which is genuinely better for anything
-- complicated and is overkill for one regex.
--
--   a. lower case, because the unique index below has to mean what a human
--      expects;
--   b. strip anything that is not a letter, a digit, a dot, an underscore or a
--      hyphen, and then strip those from the ends too;
--   c. pad anything left shorter than three characters, since Username rejects
--      it and an account nobody can sign in to is not a successful migration.
--
-- The id is appended to guarantee uniqueness rather than hoped away. Two
-- addresses at different domains can share a local part - mario@unicam.it and
-- mario@gmail.com - and a backfill that assumes otherwise fails on the unique
-- index at step 3, after it has already rewritten the table.
UPDATE fieldbook_accounts
SET username = LEFT(
        -- Trailing punctuation is stripped after the id is appended too: the
        -- id is digits, so the result always ends in an alphanumeric.
        REGEXP_REPLACE(
            LOWER(SPLIT_PART(email, '@', 1)),
            '[^a-z0-9._-]', '', 'g'
        ) || '.' || id::text,
        30)
WHERE username IS NULL;

-- Anything the expression above could not save - an address whose local part is
-- entirely punctuation, so the result starts with a dot - falls back to a name
-- built from the id alone. It is ugly, it is unique, and the person can be told
-- to pick a better one. A migration that leaves a row unusable to avoid an ugly
-- value has chosen the wrong thing to optimise.
UPDATE fieldbook_accounts
SET username = 'learner.' || id::text
WHERE username IS NULL
   OR username !~ '^[a-z0-9][a-z0-9._-]{1,28}[a-z0-9]$';


-- -----------------------------------------------------------------------------
-- 3. Tighten
-- -----------------------------------------------------------------------------
ALTER TABLE fieldbook_accounts ALTER COLUMN username SET NOT NULL;

-- The same reasoning as uk_fieldbook_accounts_email: the application lower-cases
-- every handle before it gets here, so this constraint means what a human
-- expects. Without that normalisation the database would hold Mario and mario
-- as two accounts.
ALTER TABLE fieldbook_accounts
    ADD CONSTRAINT uk_fieldbook_accounts_username UNIQUE (username);


-- -----------------------------------------------------------------------------
-- 4. fieldbook_password_resets
-- -----------------------------------------------------------------------------
-- One row per outstanding "I forgot my password" request.
--
-- The table is shaped like fieldbook_sessions on purpose - a hash of a token
-- nobody stores, an expiry, and a foreign key to the account - because it is
-- the same idea with the dial turned down: a second way to authenticate, for a
-- much shorter time, usable once.
CREATE TABLE fieldbook_password_resets (
    id             BIGINT       NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP(6) WITH TIME ZONE,
    version        BIGINT       NOT NULL,
    account_id     BIGINT       NOT NULL,

    -- SHA-256 of the token that went out in the email, hex encoded, so always
    -- exactly 64 characters. The raw value is in somebody's inbox and nowhere
    -- else: a stolen backup of this table yields hashes, and a hash cannot be
    -- put in a URL.
    token_hash     CHAR(64)     NOT NULL,

    expires_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    -- NULL while the token can still be spent. Stamping rather than deleting is
    -- what makes "was a reset requested for my account, and was it used?" a
    -- question with an answer - see PasswordResetToken.
    used_at        TIMESTAMP(6) WITH TIME ZONE,

    -- Where the request came from. Audit only; nothing is decided on it.
    requested_from VARCHAR(60),

    PRIMARY KEY (id),
    CONSTRAINT uk_fieldbook_resets_token UNIQUE (token_hash),
    CONSTRAINT fk_fb_resets_account FOREIGN KEY (account_id)
        REFERENCES fieldbook_accounts (id)
);

-- Two queries run against this table often enough to index for.
--
-- The lookup by token_hash is already served by the unique constraint above -
-- PostgreSQL implements UNIQUE with an index, so adding another one on the same
-- column buys nothing and costs a write on every insert. Worth saying out loud,
-- because "add an index on the column you look things up by" is good advice
-- that here would produce a duplicate.
--
-- What is left is the rate limit, which counts rows per account within an hour.
CREATE INDEX idx_fb_resets_account_created
    ON fieldbook_password_resets (account_id, created_at);

-- And the nightly sweep, which deletes by expiry.
CREATE INDEX idx_fb_resets_expires ON fieldbook_password_resets (expires_at);


-- =============================================================================
-- A note on what is NOT here
-- =============================================================================
-- No ON DELETE CASCADE on fk_fb_resets_account, matching every other foreign
-- key in V3. AccountService.deleteAccount removes these rows explicitly, in
-- dependency order, in one transaction - see the note at the end of V3 for why
-- that is worth the extra line.
--
-- No email verification table either. Reset now exists; proving that an address
-- belongs to the person who typed it still does not, and that gap is written
-- down in docs/ACCOUNTS.md rather than quietly left out.
-- =============================================================================
