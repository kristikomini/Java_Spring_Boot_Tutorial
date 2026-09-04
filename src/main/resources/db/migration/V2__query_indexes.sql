-- =============================================================================
-- V2  INDEXES FOR THE QUERIES THIS APPLICATION ACTUALLY RUNS
-- =============================================================================
-- Deliberately a separate migration from V1, because that is how it happens in
-- real life: the tables arrive with the feature, and the indexes arrive after
-- someone reads an execution plan. Keeping them apart means the history says
-- when each index was added and - through the commit that carries this file -
-- why.
--
-- Every index below pays for one named query. An index nobody uses is pure
-- cost: storage, plus work on every INSERT, UPDATE and DELETE. If you cannot
-- name the query, do not create the index.
-- =============================================================================

-- The FK column on the owning side. PostgreSQL indexes the primary key but NOT
-- the foreign key, so without this every "which courses does this professor
-- teach" and every cascade check scans the table.
CREATE INDEX idx_courses_professor
    ON courses (professor_id);

-- Serves the course catalogue: WHERE semester = ? AND academic_year = ?.
-- Column order matters. This index also serves a query on semester alone; it
-- does not serve one on academic_year alone - the leftmost-prefix rule.
CREATE INDEX idx_courses_semester_year
    ON courses (semester, academic_year);

-- The seat count: SELECT count(*) FROM enrollments WHERE course_id = ? AND
-- status <> 'WITHDRAWN'. This is the hottest read in the application - it runs
-- inside the enrollment transaction, holding a row lock on the course while it
-- does - so it is the one index here that is not optional.
CREATE INDEX idx_enrollments_course_status
    ON enrollments (course_id, status);

-- "What is this student enrolled on", and the prerequisite check.
CREATE INDEX idx_enrollments_student
    ON enrollments (student_id);

-- Student search by surname.
CREATE INDEX idx_students_last_name
    ON students (last_name);

-- Administrative filters on status. Low cardinality - four values - so this is
-- the index most likely to be ignored by the planner on a small table. It is
-- kept because the status filter is almost always combined with a sort, and
-- because it makes a good example: run EXPLAIN and find out whether your
-- database is using it. If it is not, delete it. See the performance chapter.
CREATE INDEX idx_students_status
    ON students (status);
