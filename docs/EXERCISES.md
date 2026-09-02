# Exercises

Reading correct code builds recognition. Writing it builds recall. These four
exercises are the difference between nodding at `@Transactional` and being able
to place a transaction boundary yourself and defend where you put it.

Each one is a stub that throws, plus a test suite that specifies exactly what it
should do. **The failing test is the specification.** Read it before writing a
line.

---

## Running them

Exercise tests are tagged `exercise` and are **excluded from the normal build**,
so `mvn verify` stays an honest 60/60 signal about the application itself. Run
them with the `exercises` profile:

```bash
mvn test -Pexercises
```

```bash
mvn test -Pexercises -Dtest=Ex2CourseWindowTest
```

All 31 fail on a fresh checkout. That is correct — it is your scoreboard.

| Command | What it tells you |
|---|---|
| `mvn verify` | Is the *application* still healthy? (60 tests, must stay green) |
| `mvn test -Pexercises` | How far through the exercises am I? (31 tests) |

---

## The four

### 1 · A query — `Ex1StudentQueries` · *warm-up*

Write JPQL that finds students by enrollment year, ordered by student number.
Practises named parameters, ordering, and the fact that JPQL queries *entities*,
not tables. Four tests.

Compare your answer afterwards with `StudentRepository.search(...)`, which solves
a harder version with the Criteria API.

### 2 · A domain rule — `Ex2CourseWindow` · *small, but exacting*

`Course.isEnrollmentOpen` answers yes/no. Return three states instead:
`NOT_YET_OPEN`, `OPEN`, `CLOSED`.

Ten tests, most of them sitting exactly on a boundary — one nanosecond before
opening, exactly at opening, exactly at closing. The window is half-open
`[opens, closes)` and you must match the existing method precisely. One test
asserts your answer agrees with `isEnrollmentOpen` at seven different instants.

### 3 · A use case — `Ex3TransferService` · **the one that matters**

Move a student from one course to another atomically. Eight rules in order, and
twelve tests covering every one.

This is the exercise worth spending real time on. It forces you through the
transaction boundary, the pessimistic lock on the target course, the state
machine, the capacity check, and the discipline of validating everything *before*
mutating anything. `EnrollmentService.enroll` solves most of the same problems —
read it, don't copy it.

### 4 · An endpoint — `Ex4TransferResource` · *short, depends on 3*

Expose the transfer as `POST /api/exercises/transfer`. Five tests.

The lesson is how little a resource does: delegate, map, choose a status code.
No try/catch — the exception mappers already turn your service's exceptions into
404 and 409, and catching them here would duplicate that and get it subtly wrong.

Once 3 and 4 both pass, the endpoint is live on your running server:

```bash
curl -X POST http://localhost:8280/enrollment/api/exercises/transfer -H "Content-Type: application/json" -d '{"studentId":102,"fromCourseId":52,"toCourseId":53}'
```

Until then it returns 500 — the catch-all mapper doing its job on the
`UnsupportedOperationException` from the stub.

---

## Two things you will hit

**`mvn verify` and `mvn test -Pexercises` disagree.** That is by design. The
first tells you the application works; the second tells you how far you have got.
Never "fix" a failing exercise test by editing the test.

**Exercise 4's tests need RESTEasy on the classpath.** `pom.xml` has a
test-scoped `resteasy-core` dependency for exactly this reason:
`jakarta.jakartaee-api` is `provided`, so it gives you interfaces that compile
and nothing that runs. Without an implementation, `Response.ok(...).build()`
throws `ClassNotFoundException: Provider for jakarta.ws.rs.ext.RuntimeDelegate
cannot be found`. That is fieldbook chapter 1 met in practice.

---

## When you are properly stuck

Struggle first — the difficulty is the mechanism, not an obstacle to it. But a
self-taught learner with no one to ask needs an answer key, so here is one.

<details>
<summary><strong>Exercise 1 — solution</strong></summary>

```java
return em.createQuery(
                "SELECT s FROM Student s WHERE s.enrollmentYear = :year "
                        + "ORDER BY s.studentNumber ASC", Student.class)
        .setParameter("year", year)
        .getResultList();
```
</details>

<details>
<summary><strong>Exercise 2 — solution</strong></summary>

```java
Objects.requireNonNull(course, "course must not be null");
Objects.requireNonNull(now, "now must not be null");
if (now.isBefore(course.getEnrollmentOpensAt())) {
    return Window.NOT_YET_OPEN;
}
if (now.isBefore(course.getEnrollmentClosesAt())) {
    return Window.OPEN;
}
return Window.CLOSED;
```

Note there is no `isAfter` anywhere. Two `isBefore` checks in the right order
express a half-open interval without a single boundary special case — which is
most of why half-open intervals are the convention.
</details>

<details>
<summary><strong>Exercise 3 — solution</strong></summary>

```java
Instant now = clock.instant();

Student student = studentRepository.findById(studentId)
        .orElseThrow(() -> ResourceNotFoundException.of("Student", studentId));

if (fromCourseId.equals(toCourseId)) {
    throw BusinessRuleViolationException.illegalStateTransition(
            "Source and target course are the same");
}

courseRepository.findById(fromCourseId)
        .orElseThrow(() -> ResourceNotFoundException.of("Course", fromCourseId));

Course target = courseRepository.findByIdWithPessimisticLock(toCourseId)
        .orElseThrow(() -> ResourceNotFoundException.of("Course", toCourseId));

Enrollment source = enrollmentRepository
        .findByStudentAndCourse(studentId, fromCourseId)
        .orElseThrow(() -> ResourceNotFoundException.of("Enrollment", fromCourseId));

if (source.getStatus() != EnrollmentStatus.ACTIVE) {
    throw BusinessRuleViolationException.illegalStateTransition(
            "Only an ACTIVE enrollment can be transferred, was " + source.getStatus());
}

enrollmentRepository.findByStudentAndCourse(studentId, toCourseId)
        .ifPresent(existing -> {
            throw new DuplicateResourceException(
                    "Student is already enrolled in " + target.getCode());
        });

if (!target.isEnrollmentOpen(now)) {
    throw BusinessRuleViolationException.enrollmentWindowClosed(target.getCode());
}

if (enrollmentRepository.countOccupiedSeats(toCourseId) >= target.getCapacity()) {
    throw BusinessRuleViolationException.courseFull(target.getCode(), target.getCapacity());
}

source.withdraw(now);
return enrollmentRepository.save(Enrollment.create(student, target, now));
```

Every check precedes every mutation. `source.withdraw(now)` is the first line
that changes anything, and by then nothing can fail. The rollback is a safety
net, not the mechanism.
</details>

<details>
<summary><strong>Exercise 4 — solution</strong></summary>

```java
Enrollment moved = transferService.transfer(
        request.getStudentId(), request.getFromCourseId(), request.getToCourseId());
return Response.ok(enrollmentMapper.toResponse(moved)).build();
```

Two lines. If yours is much longer, something has leaked up from the service.
</details>

---

## After these

The README's *Known limitations* section is a list of larger exercises, each
scoped to a real gap in this codebase: add authentication, replace
`hbm2ddl.auto=update` with Flyway, detect prerequisite cycles beyond direct ones,
version the API. Fieldbook chapter 10 breaks the authentication one into steps.

Also worth doing: `./scripts/break.sh` (see [BREAKING.md](BREAKING.md)) — the
inverse skill. These exercises ask you to make something work; that script asks
you to watch something fail and understand why.
