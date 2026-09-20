-- ============================================================================
-- V7 - CHAR(64) BECOMES VARCHAR(64), AND WHY char(n) IS A TRAP
-- ============================================================================
-- The two token_hash columns were declared CHAR(64). The reasoning was sound
-- on its face: a SHA-256 hex digest is always exactly 64 characters, so a
-- fixed-width type says so in the schema. Hibernate disagreed, and schema
-- validation failed the integration tests:
--
--   Schema-validation: wrong column type encountered in column [token_hash]
--   in table [fieldbook_password_resets]; found [bpchar (Types#CHAR)],
--   but expecting [varchar (Types#VARCHAR)]
--
-- A JPA String maps to varchar. Getting char instead requires saying so with
-- columnDefinition, and the mappings never did.
--
-- WHICH SIDE SHOULD MOVE? The schema, for a reason that has nothing to do with
-- Hibernate: PostgreSQL's own documentation advises against char(n). It is not
-- faster - there is no storage saving over varchar - and it is blank-padded,
-- so a 64-character value stored in char(70) comes back with six trailing
-- spaces that no application asked for. The padding has produced a long
-- history of comparisons that fail for invisible reasons.
--
-- Here the values genuinely are 64 characters, so no padding exists and the
-- conversion cannot lose data. The cast is a metadata-and-rewrite operation on
-- two small tables.
--
-- WHY A NEW MIGRATION RATHER THAN EDITING V3 AND V5. Flyway records a checksum
-- of every migration it has applied and refuses to start if a file it already
-- ran has changed. Editing V3 in place would work exactly once - on a database
-- that has never seen it - and break every environment that had. A migration
-- is an immutable historical record; the schema moves forward by adding to it.
-- Fieldbook chapter 30 makes this argument at length.
-- ============================================================================

ALTER TABLE fieldbook_sessions
    ALTER COLUMN token_hash TYPE VARCHAR(64);

ALTER TABLE fieldbook_password_resets
    ALTER COLUMN token_hash TYPE VARCHAR(64);
