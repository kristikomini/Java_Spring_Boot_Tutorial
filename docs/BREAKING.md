# Breaking it on purpose

`scripts/break.sh` introduces one classic Jakarta EE bug at a time, tells you
exactly what to run to observe it, and puts it back with `git checkout`.

The exercises in [EXERCISES.md](EXERCISES.md) ask you to make something work.
This is the inverse skill, and arguably the more useful one: watching a specific
mistake produce a specific symptom, so that when you meet the symptom in
someone else's code you already know the shape of the cause.

```bash
./scripts/break.sh list
./scripts/break.sh fetch-plan
mvn package        # WildFly hot-redeploys
./scripts/break.sh restore
```

Every break is a single-line edit to a tracked file, and the script refuses to
touch a file that already has uncommitted changes — so `restore` can never
discard your own work.

---

## The four breaks

All of the numbers below were measured against this application running on
WildFly 41 with the seeded dataset. You should see the same.

### `fetch-plan` — fails loudly

Removes `LEFT JOIN FETCH c.prerequisites` from `CourseRepository`.

`GET /courses/55` goes from **200 to 500**, with `LazyInitializationException`
thrown in `CourseMapper`. The mapper reads `getPrerequisites()` after the
transaction has committed, so the entity is detached and there is no session left
to run the lazy query.

The useful follow-up: run `mvn verify`. `CourseRepositoryIT` **catches it**,
because that test calls `entityManager.clear()` before asserting.

### `blind-test` — the test that lies

Comments out that one `entityManager.clear()` line.

Now apply `fetch-plan` as well and run `mvn verify`: **BUILD SUCCESS**, while the
endpoint is returning 500 to real users. With the persistence context still open,
the lazy getter quietly fires another query and succeeds. The test was passing
for a reason that does not exist in production.

> A test configuration that differs from production will happily prove the wrong
> thing. The value of a test is not that it passes — it is that it *would* have
> failed.

### `n-plus-one` — fails silently

Replaces the batched `countOccupiedSeatsByCourse` with a loop.

One list request goes from **3 SQL statements to 7** — and stays **200**. The
four extra queries are identical except for the id. With five hundred courses it
is five hundred round trips for one page.

This is the version that ships. Nothing errors, nothing is slow with test data,
and the only way to notice is to read the SQL log — which is exactly why this
project sets `org.hibernate.SQL` to DEBUG.

### `no-lock` — fails only under concurrency

Swaps `findByIdWithPessimisticLock` for `findById` in `EnrollmentService`.

`FOR NO KEY UPDATE` **disappears** from the SQL. Every test still passes and
every request still works, because the bug needs two requests interleaving: both
read "one seat left", both decide it is fine, both insert.

Race conditions do not appear in single-threaded tests. This one is included
precisely because you *cannot* observe the failure — only the missing defence.
You have to reason about it from the code, which is the actual skill.

---

## The pattern worth extracting

Four bugs, four completely different failure modes:

| Break | Endpoint | Test suite | When it bites |
|---|---|---|---|
| `fetch-plan` | 500 | catches it | immediately |
| `blind-test` + `fetch-plan` | 500 | **passes** | immediately, unnoticed |
| `n-plus-one` | 200 | passes | at scale |
| `no-lock` | 200 | passes | under concurrency |

Only one of the four is caught by the tests. That is not a criticism of this test
suite — it is the normal state of affairs, and knowing which categories your
tests *cannot* cover is what tells you where to be careful by other means.

---

## Housekeeping

```bash
./scripts/break.sh status     # what is currently broken
./scripts/break.sh restore    # put everything back
```

After restoring, `mvn verify` should be back to 60/60 and `mvn package` will
redeploy the working build.
