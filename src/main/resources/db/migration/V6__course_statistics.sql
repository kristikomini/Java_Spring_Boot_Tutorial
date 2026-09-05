-- ============================================================================
-- V6 - A REPORTING TABLE, AND THE FIRST OLAP-SHAPED THING IN THIS SCHEMA
-- ============================================================================
-- Everything before this migration is OLTP: narrow rows, heavily indexed,
-- written constantly, read one row at a time inside a transaction. The
-- enrollments table exists to answer "may this student take this seat, right
-- now" correctly under concurrency.
--
-- This table is the other shape. It is written once every few minutes by a
-- scheduled job, never inside a user transaction, and read by queries that scan
-- most of it. It stores an ANSWER rather than a fact.
--
-- WHY MATERIALISE AT ALL, when the query could just be run on demand? Three
-- reasons, and they are the standard argument for a reporting layer:
--
--   1. The aggregate scans every enrollment for a course. Running that on the
--      transactional tables during enrollment week competes for exactly the
--      rows and locks the enrollment path needs. Fieldbook chapter 33 calls
--      reporting "a good cut" for the same reason.
--   2. A dashboard asks the same question a hundred times a minute and the
--      answer changes every few minutes. Computing it once is a hundredfold
--      saving.
--   3. It can be a historical record. A row here is what the numbers WERE at
--      computed_at, which the live tables can never tell you about last term.
--
-- WHY NOT A MATERIALIZED VIEW, which PostgreSQL has and which does exactly
-- this? Because REFRESH MATERIALIZED VIEW takes a lock that blocks readers
-- (unless CONCURRENTLY, which needs a unique index and is slower), and because
-- the refresh logic then lives in the database where it cannot be unit-tested,
-- versioned with the application, or given a retry. Both answers are defensible.
-- A table plus a job is the one that fits an application team; a materialized
-- view is the one that fits a data team. Being able to say that is the point.
--
-- OWNED BY THE SPRING SERVICE. The Jakarta EE application does not map this
-- table and does not need to - see the note in ProfessorRepository about
-- ddl-auto=validate only checking columns you HAVE mapped. This is the shape a
-- read-model owned by a second service actually takes before anybody extracts
-- it into a separate database.
-- ============================================================================

CREATE TABLE course_statistics (
    -- Not a surrogate key: the course IS the identity of this row. One row per
    -- course, replaced on every refresh, so the natural key is the right key
    -- and an id column would be pure ceremony.
    course_id           BIGINT       NOT NULL,

    -- DENORMALISED ON PURPOSE, and this is the line that most offends people
    -- coming from OLTP. The code and title are already in `courses`, so copying
    -- them here is duplication - and it is correct, because the whole point of
    -- a reporting row is that a dashboard can read it WITHOUT joining back to
    -- the transactional tables. Normalisation optimises for consistent writes;
    -- a reporting table optimises for cheap reads. Different job, different
    -- rules.
    course_code         VARCHAR(12)  NOT NULL,
    course_title        VARCHAR(160) NOT NULL,
    academic_year       INTEGER      NOT NULL,
    semester            VARCHAR(10)  NOT NULL,
    department          VARCHAR(120) NOT NULL,

    capacity            INTEGER      NOT NULL,
    total_enrollments   INTEGER      NOT NULL,
    active_count        INTEGER      NOT NULL,
    completed_count     INTEGER      NOT NULL,
    failed_count        INTEGER      NOT NULL,
    withdrawn_count     INTEGER      NOT NULL,

    -- NUMERIC, not DOUBLE PRECISION. A float cannot represent 0.1 exactly, and
    -- a percentage that renders as 66.66666666666667 in a report is the visible
    -- symptom of a choice that also makes sums fail to add up. Anything a human
    -- reads as a decimal number, or that money touches, is NUMERIC.
    pass_rate           NUMERIC(5,2),
    average_grade       NUMERIC(4,2),
    fill_rate           NUMERIC(5,2)  NOT NULL,

    -- When this row was computed. A reporting row without this is unusable:
    -- nobody can tell whether they are looking at live data or at something
    -- from before the job last failed.
    computed_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    PRIMARY KEY (course_id),
    CONSTRAINT fk_course_statistics_course
        FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE CASCADE
);

-- The index a dashboard filter actually uses: "show me this year, this
-- semester". Column order matters - academic_year first, because it is the
-- filter that is always present, and a query filtering only on semester is not
-- one anybody asks. Fieldbook chapter 07 has the argument for why the reverse
-- order would be nearly useless here.
CREATE INDEX idx_course_statistics_year_semester
    ON course_statistics (academic_year, semester);

-- Supports the leaderboard: "worst fill rates first". A descending index,
-- because that is the direction the report is sorted in and a plain ascending
-- index cannot serve an ORDER BY DESC without a backwards scan.
CREATE INDEX idx_course_statistics_fill_rate
    ON course_statistics (fill_rate);

COMMENT ON TABLE course_statistics IS
    'Materialised course metrics. Written by the Spring reporting job, never in a user transaction. Read-only for dashboards.';
