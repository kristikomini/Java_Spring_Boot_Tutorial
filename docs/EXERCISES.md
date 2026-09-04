# Exercises

Reading correct code builds recognition. Writing it builds recall. These six
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

All 98 fail on a fresh checkout. That is correct — it is your scoreboard.

| Command | What it tells you |
|---|---|
| `mvn verify` | Is the *application* still healthy? (126 tests, must stay green) |
| `mvn test -Pexercises` | How far through the exercises am I? (98 tests) |

---

## The six

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

### 5 · The ten interview katas — `Ex5InterviewKatas` · *breadth, not depth*

Reverse a string, palindrome, find duplicates, first non-repeated character,
word frequencies, missing number, merge two sorted arrays, binary search,
second-highest distinct value, balanced parentheses. Fifty-nine tests.

None is difficult and all of them get asked. The tests are shaped like an
interviewer: the first case in each block is the happy path and the rest are the
edge cases — empty, single element, `null`, duplicates, and two deliberate
overflow traps (the sum in the missing-number kata, the midpoint in binary
search). Every method's Javadoc names the expected complexity, because saying it
out loud is half of what is being marked.

Rehearse them the way they will happen: say the approach before you type, and
name the edge cases you are not handling.

### 6 · The report query — `Ex6EnrollmentReport` · *the SQL question*

One JPQL query with `JOIN`, `WHERE`, `GROUP BY`, `HAVING` and `ORDER BY`:
courses with at least *n* non-withdrawn enrollments, most popular first. Eight
tests, against H2.

Asked more often than any Java problem. The marks are in knowing that the
"not withdrawn" filter belongs in `WHERE` (it removes rows before grouping) while
"at least n" belongs in `HAVING` (it removes groups after), and in noticing that
a course with no qualifying enrollments must not appear as a zero.

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
cannot be found`. That is fieldbook chapter 28 — Maven, scopes and the dependency tree — met in
practice.

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

<details>
<summary><strong>Exercise 5 — solutions</strong></summary>

```java
// 1 · reverse
if (input == null) return null;
StringBuilder out = new StringBuilder(input.length());
for (int i = input.length() - 1; i >= 0; i--) out.append(input.charAt(i));
return out.toString();

// 2 · isPalindrome — two pointers, skipping non-alphanumerics
if (input == null) return false;
int lo = 0, hi = input.length() - 1;
while (lo < hi) {
    while (lo < hi && !Character.isLetterOrDigit(input.charAt(lo))) lo++;
    while (lo < hi && !Character.isLetterOrDigit(input.charAt(hi))) hi--;
    if (Character.toLowerCase(input.charAt(lo)) != Character.toLowerCase(input.charAt(hi)))
        return false;
    lo++; hi--;
}
return true;

// 3 · findDuplicates — add() returning false is the whole trick
List<Integer> found = new ArrayList<>();
if (values == null) return found;
Set<Integer> seen = new HashSet<>(), reported = new HashSet<>();
for (int v : values) if (!seen.add(v) && reported.add(v)) found.add(v);
return found;

// 4 · firstNonRepeated — LinkedHashMap keeps insertion order
if (input == null || input.isEmpty()) return null;
Map<Character, Integer> counts = new LinkedHashMap<>();
for (char c : input.toCharArray()) counts.merge(c, 1, Integer::sum);
for (Map.Entry<Character, Integer> e : counts.entrySet())
    if (e.getValue() == 1) return e.getKey();
return null;

// 5 · wordFrequencies
Map<String, Integer> counts = new LinkedHashMap<>();
if (text == null) return counts;
for (String raw : text.trim().split("\\s+")) {
    String word = raw.replaceAll("^[^\\p{IsAlphabetic}\\p{IsDigit}]+|[^\\p{IsAlphabetic}\\p{IsDigit}]+$", "")
                     .toLowerCase();
    if (!word.isEmpty()) counts.merge(word, 1, Integer::sum);
}
return counts;

// 6 · findMissingNumber — long, or the sum overflows for large n
if (values == null) throw new IllegalArgumentException("values must not be null");
int n = values.length + 1;
long expected = (long) n * (n + 1) / 2, actual = 0;
for (int v : values) actual += v;
return (int) (expected - actual);

// 7 · mergeSorted
int[] a = left  == null ? new int[0] : left;
int[] b = right == null ? new int[0] : right;
int[] out = new int[a.length + b.length];
int i = 0, j = 0, k = 0;
while (i < a.length && j < b.length) out[k++] = a[i] <= b[j] ? a[i++] : b[j++];
while (i < a.length) out[k++] = a[i++];
while (j < b.length) out[k++] = b[j++];
return out;

// 8 · binarySearch — note the midpoint
if (sorted == null) return -1;
int lo = 0, hi = sorted.length - 1;
while (lo <= hi) {
    int mid = lo + (hi - lo) / 2;          // NOT (lo + hi) / 2
    if (sorted[mid] == target) return mid;
    if (sorted[mid] <  target) lo = mid + 1; else hi = mid - 1;
}
return -1;

// 9 · secondHighest — distinct, so skip equals
if (values == null || values.length < 2) throw new IllegalArgumentException("need two distinct values");
long best = Long.MIN_VALUE, second = Long.MIN_VALUE;
for (int v : values) {
    if (v > best) { second = best; best = v; }
    else if (v < best && v > second) second = v;
}
if (second == Long.MIN_VALUE) throw new IllegalArgumentException("need two distinct values");
return (int) second;

// 10 · isBalanced
if (input == null) return true;
Deque<Character> stack = new ArrayDeque<>();
for (char c : input.toCharArray()) {
    if (c == '(' || c == '[' || c == '{') stack.push(c);
    else if (c == ')' || c == ']' || c == '}') {
        if (stack.isEmpty()) return false;             // the case people forget
        char open = stack.pop();
        if ((c == ')' && open != '(') || (c == ']' && open != '[') || (c == '}' && open != '{'))
            return false;
    }
}
return stack.isEmpty();
```
</details>

<details>
<summary><strong>Exercise 6 — solution</strong></summary>

```java
return em.createQuery(
                "SELECT c.code, COUNT(e) "
              + "FROM Course c JOIN c.enrollments e "
              + "WHERE e.status <> :withdrawn "          // filters ROWS, before grouping
              + "GROUP BY c.code "
              + "HAVING COUNT(e) >= :minimum "           // filters GROUPS, after
              + "ORDER BY COUNT(e) DESC, c.code ASC", Object[].class)
        .setParameter("withdrawn", EnrollmentStatus.WITHDRAWN)
        .setParameter("minimum", (long) minimumEnrollments)
        .getResultList();
```

The inner `JOIN` is what keeps courses with no enrollments out of the result
entirely. Use a `LEFT JOIN` and every empty course arrives with a count of zero
for the `HAVING` to remove — which also works, and is worth writing once to see
the difference.
</details>

---

## After these

The README's *Known limitations* section is a list of larger exercises, each
scoped to a real gap in this codebase: add authentication, detect prerequisite
cycles beyond direct ones, version the API. Fieldbook chapter 15 breaks the
authentication one into steps.

The Flyway one is now half done for you: `src/main/resources/db/migration` holds
the baseline and the indexes, and fieldbook chapter 29 walks the three steps that
finish it — `flyway:baseline`, then `validate` instead of `update` in
`persistence.xml`, then adding a column and watching the deployment refuse.

Also worth doing: `./scripts/break.sh` (see [BREAKING.md](BREAKING.md)) — the
inverse skill. These exercises ask you to make something work; that script asks
you to watch something fail and understand why.
