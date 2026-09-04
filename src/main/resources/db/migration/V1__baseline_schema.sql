-- =============================================================================
-- V1  BASELINE SCHEMA
-- =============================================================================
-- This is the schema Hibernate generates from the entity mappings, written out
-- as a file that can be read, reviewed and replayed in order. That difference
-- is the whole argument for migrations:
--
--   hbm2ddl "update"          this file
--   -----------------------   -------------------------------------------------
--   invisible                 reviewed in a pull request like any other code
--   never removes anything    says exactly what it does, including drops
--   no history                V1, V2, V3 - the schema has a git log
--   no rollback               a forward migration can be written to undo it
--   differs per environment   identical everywhere, by construction
--
-- Naming: V<version>__<description>.sql, two underscores. Flyway applies files
-- in version order, records each one in its own `flyway_schema_history` table
-- with a checksum, and refuses to start if a file that has already run has
-- since been edited. That last rule is the one that surprises people, and it is
-- the point: an applied migration is history, and history is immutable. To
-- change something, add V4.
--
-- This file was generated from the mappings rather than typed by hand - see the
-- fieldbook chapter on migrations for the one-line command that does it. That is
-- the honest way to adopt Flyway on a project that already has a schema.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Identifier sequence
-- -----------------------------------------------------------------------------
-- One sequence for every entity, with allocationSize 50: the application takes
-- fifty ids per round trip instead of one. See BaseEntity, and the performance
-- chapter for why this pairs with JDBC batching.
CREATE SEQUENCE app_id_seq START WITH 1 INCREMENT BY 50;

-- -----------------------------------------------------------------------------
-- professors
-- -----------------------------------------------------------------------------
CREATE TABLE professors (
    id            BIGINT       NOT NULL,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITH TIME ZONE,
    version       BIGINT       NOT NULL,
    staff_number  VARCHAR(10)  NOT NULL,
    first_name    VARCHAR(80)  NOT NULL,
    last_name     VARCHAR(80)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    title         VARCHAR(30)  NOT NULL,
    department    VARCHAR(120) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_professors_staff_number UNIQUE (staff_number),
    CONSTRAINT uk_professors_email        UNIQUE (email),
    -- The enum is stored as its name, and the database refuses anything else.
    -- Belt and braces: the application validates too, but the constraint is what
    -- survives a bad script, a manual UPDATE and a second application.
    CONSTRAINT ck_professors_title
        CHECK (title IN ('ASSISTANT_PROFESSOR', 'ASSOCIATE_PROFESSOR', 'FULL_PROFESSOR'))
);

-- -----------------------------------------------------------------------------
-- students
-- -----------------------------------------------------------------------------
CREATE TABLE students (
    id              BIGINT       NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE,
    version         BIGINT       NOT NULL,
    student_number  VARCHAR(10)  NOT NULL,
    first_name      VARCHAR(80)  NOT NULL,
    last_name       VARCHAR(80)  NOT NULL,
    email           VARCHAR(255) NOT NULL,
    date_of_birth   DATE         NOT NULL,
    enrollment_year INTEGER      NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_students_student_number UNIQUE (student_number),
    CONSTRAINT uk_students_email          UNIQUE (email),
    CONSTRAINT ck_students_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'GRADUATED', 'WITHDRAWN'))
);

-- -----------------------------------------------------------------------------
-- courses
-- -----------------------------------------------------------------------------
CREATE TABLE courses (
    id                   BIGINT        NOT NULL,
    created_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP(6) WITH TIME ZONE,
    version              BIGINT        NOT NULL,
    code                 VARCHAR(12)   NOT NULL,
    title                VARCHAR(160)  NOT NULL,
    description          VARCHAR(2000),
    credits              INTEGER       NOT NULL,
    capacity             INTEGER       NOT NULL,
    semester             VARCHAR(10)   NOT NULL,
    academic_year        INTEGER       NOT NULL,
    enrollment_opens_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    enrollment_closes_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    professor_id         BIGINT        NOT NULL,
    PRIMARY KEY (id),
    -- A course code is unique WITHIN an academic year, not globally: CS101 runs
    -- again next year and is a different row. Composite uniqueness is the kind
    -- of rule that is obvious in the domain and invisible in the code unless the
    -- database states it.
    CONSTRAINT uk_courses_code_year UNIQUE (code, academic_year),
    CONSTRAINT ck_courses_semester  CHECK (semester IN ('FALL', 'SPRING'))
);

-- -----------------------------------------------------------------------------
-- enrollments
-- -----------------------------------------------------------------------------
CREATE TABLE enrollments (
    id           BIGINT      NOT NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP(6) WITH TIME ZONE,
    version      BIGINT      NOT NULL,
    student_id   BIGINT      NOT NULL,
    course_id    BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL,
    enrolled_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    grade        INTEGER,
    with_honours BOOLEAN     NOT NULL,
    PRIMARY KEY (id),
    -- The rule "a student may hold at most one enrollment per course" enforced
    -- where it cannot be raced. The service checks it too, for a good error
    -- message; this constraint is what makes the check true under concurrency.
    CONSTRAINT uk_enrollments_student_course UNIQUE (student_id, course_id),
    CONSTRAINT ck_enrollments_status
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'WITHDRAWN', 'FAILED'))
);

-- -----------------------------------------------------------------------------
-- course_prerequisites  (self-referencing many-to-many join table)
-- -----------------------------------------------------------------------------
CREATE TABLE course_prerequisites (
    course_id       BIGINT NOT NULL,
    prerequisite_id BIGINT NOT NULL,
    PRIMARY KEY (course_id, prerequisite_id)
);

-- -----------------------------------------------------------------------------
-- Foreign keys, added after every table exists so the file has no ordering trap
-- -----------------------------------------------------------------------------
ALTER TABLE courses
    ADD CONSTRAINT fk_courses_professor
    FOREIGN KEY (professor_id) REFERENCES professors;

ALTER TABLE enrollments
    ADD CONSTRAINT fk_enrollments_student
    FOREIGN KEY (student_id) REFERENCES students;

ALTER TABLE enrollments
    ADD CONSTRAINT fk_enrollments_course
    FOREIGN KEY (course_id) REFERENCES courses;

ALTER TABLE course_prerequisites
    ADD CONSTRAINT fk_prereq_course
    FOREIGN KEY (course_id) REFERENCES courses;

ALTER TABLE course_prerequisites
    ADD CONSTRAINT fk_prereq_prerequisite
    FOREIGN KEY (prerequisite_id) REFERENCES courses;
