# UNICAM Course Enrollment Service

A **Jakarta EE 10** reference application, built to be read.

Every class in this project carries comments explaining *what* it does, *why* it
is built that way, and *what the industry calls it*. The goal is not a working
application — it is that you finish able to hold your own in a conversation
about enterprise Java.

**Domain:** university course enrollment. Students enrol in courses, subject to
capacity limits, enrollment windows, prerequisites and academic standing. Exams
produce grades on the Italian 18–30 scale, with *30 e lode*.

---

## What you will learn

| Area | Concepts covered |
|---|---|
| **Persistence (JPA)** | Entities, `@MappedSuperclass`, embeddables, all four relationship types, self-referencing many-to-many, association entities, lazy vs eager, `JOIN FETCH`, the N+1 problem, named queries, the Criteria API, optimistic & pessimistic locking, bulk operations, dirty checking |
| **Dependency injection (CDI)** | Scopes, producer methods, `InjectionPoint`, interceptors and interceptor bindings, events and observers, transactional observers |
| **Transactions (JTA)** | `@Transactional`, propagation types, rollback rules, why checked exceptions do *not* roll back, self-invocation |
| **REST (JAX-RS)** | Resources, sub-resources, `@BeanParam`, content negotiation, exception mappers, filters, correct status codes, RFC 7807 errors |
| **Validation** | Built-in constraints, custom constraints, cross-field validation, validation at multiple layers |
| **Architecture** | Layering, DTOs, mappers, the repository pattern, command objects, domain events, rich vs anemic domain models |
| **Operations** | Correlation IDs, MDC logging, connection pooling, scheduled jobs, idempotent startup, Docker |
| **Testing** | Unit vs integration tests, mocking, test data builders, boundary testing, parameterized tests, controlling the clock |

---

## Quick start

**Prerequisites:** JDK 11+, Maven, Docker Desktop. *(Both Maven and the JDK are
already installed and on your PATH.)*

```bash
# 1. Build — runs 60 tests and writes the WAR into docker/deployments/
mvn clean verify

# 2. Start PostgreSQL and WildFly
docker compose up -d --build

# 3. Watch it come up (the first build takes a few minutes)
docker compose logs -f wildfly
```

Then open **<http://localhost:8280/enrollment/>** for the API index, or:

```bash
curl http://localhost:8280/enrollment/api/courses/open
```

| What | Where |
|---|---|
| API index | <http://localhost:8280/enrollment/> |
| API base | <http://localhost:8280/enrollment/api> |
| Admin console | <http://localhost:9990> — `admin` / `Admin#2026` |
| PostgreSQL | `localhost:55433` — db `enrollment`, user `enrollment`, password `enrollment` |

> **Why 8280 and 55433?** Your machine already runs other projects on 8080,
> 5432 and 55432. Only the *host* side of the port mapping changed — inside the
> Docker network WildFly still reaches the database as `postgres:5432`, and the
> application still serves on 8080 in its container. Changing a host port never
> requires changing application configuration, which is exactly the point of
> container networking. Both are set in `docker-compose.yml`.

### The development loop

```bash
mvn package        # WildFly hot-redeploys within a second or two
```

Maven writes the WAR straight into `docker/deployments/`, which is bind-mounted
into WildFly's deployment scanner, and then writes an `enrollment.war.dodeploy`
marker beside it. The marker is the instruction: the scanner picks it up on its
next pass, deploys, deletes it, and leaves `enrollment.war.deployed` behind (or
`enrollment.war.failed`, containing the reason). No restart, no copying.

> **Why a marker and not just the timestamp?** The scanner's default mode
> redeploys whenever the archive looks newer than its own `.deployed` marker.
> Across a Docker bind mount on Windows and macOS the marker loses its
> sub-second precision on the way to disk while the WAR keeps its own, so the
> WAR is *permanently* newer and the server redeploys itself every few seconds,
> forever. It does not announce itself as an error: you see requests
> intermittently 404, and 500s carrying `IJ000459: Transaction is not active`.
> `docker/wildfly/configure.cli` §5 turns auto-deploy off to make deployment
> explicit, and the pom writes the marker so `mvn package` stays one command.

---

## Project layout

```
JavaEE/
├── pom.xml                       The build. Read this first.
├── docker-compose.yml            PostgreSQL + WildFly
├── docker/wildfly/
│   ├── Dockerfile                WildFly + PostgreSQL driver
│   └── configure.cli             Datasource, logging, deploy mode, as code
├── scripts/api-tour.sh           41 assertions over the live API
├── docs/ARCHITECTURE.md          Layer-by-layer rationale
├── docs/GLOSSARY.md              The vocabulary
├── docs/EXERCISES.md             Four graded exercises, specified by failing tests
├── docs/BREAKING.md              Introduce classic bugs on purpose, and observe them
├── docs/DEBUGGING.md             Attach a debugger to WildFly
├── scripts/break.sh              The break-it tool
│
└── src/
    ├── main/
    │   ├── java/it/unicam/cs/enrollment/
    │   │   ├── api/              ── REST LAYER ─────────────────────
    │   │   │   ├── rest/           JAX-RS resources (the endpoints)
    │   │   │   ├── dto/            Request & response shapes
    │   │   │   ├── mapper/         Entity ↔ DTO translation
    │   │   │   ├── exception/      Exception → HTTP status
    │   │   │   └── filter/         Correlation IDs, request logging
    │   │   │
    │   │   ├── service/          ── APPLICATION LAYER ──────────────
    │   │   │   ├── command/        Inputs to use cases
    │   │   │   └── *Service        Business rules, transactions, events
    │   │   │
    │   │   ├── repository/       ── PERSISTENCE LAYER ──────────────
    │   │   │                       Queries. No business logic.
    │   │   │
    │   │   ├── domain/           ── DOMAIN LAYER (the core) ────────
    │   │   │   ├── model/          Entities, value objects, enums
    │   │   │   ├── event/          Domain events
    │   │   │   └── validation/     Custom constraints
    │   │   │
    │   │   ├── common/             Shared: pagination, Clock, logging
    │   │   ├── config/             Bootstrap, JAX-RS activation, seeding
    │   │   └── exception/          The application's exception hierarchy
    │   │
    │   ├── resources/META-INF/persistence.xml
    │   ├── wildfly/                The .dodeploy marker Maven copies
    │   └── webapp/WEB-INF/         beans.xml, web.xml, jboss-*.xml
    │
    └── test/java/…                 Unit tests (*Test) and integration (*IT)
```

**The dependency rule:** arrows point *inward*. `api` may import `service`;
`service` may import `domain`; `domain` imports nothing of ours. That is what
lets the domain be tested with plain JUnit and reused from a job, a CLI or a
message consumer — never just a web app.

---

## The domain model

```
              ┌────────────┐
              │ Professor  │
              └─────┬──────┘
                    │ 1
                    │ teaches
                    │ *
┌─────────┐   *  ┌──┴───────┐ *      * ┌──────────┐
│ Student ├──────┤Enrollment├─────────┤  Course  │
└─────────┘      └──────────┘          └────┬─────┘
                  · status                  │ *
                  · enrolledAt              │ prerequisites
                  · grade                   │ *
                  · withHonours             └──┐
                                          (self-referencing)
```

`Enrollment` is the piece worth studying. A plain `@ManyToMany` between Student
and Course would give you a join table and nothing else — but the relationship
*carries data* (when, what status, which grade). The moment an association has
attributes of its own it stops being a relationship and becomes an entity. That
pattern is called an **association entity**, and recognising when you need one is
one of the more valuable modelling skills.

### Enrollment lifecycle

```
                 withdraw()
      ACTIVE ─────────────────► WITHDRAWN  (terminal)
         │
         │ recordPass(18–30)
         ▼
     COMPLETED  (terminal)
         ▲
         │ recordFailure()          retake()
      FAILED ◄───── ACTIVE      FAILED ─────► ACTIVE
```

The transition table lives inside the `EnrollmentStatus` enum, not scattered
across services. Illegal transitions are impossible rather than merely
discouraged.

---

## Business rules

Enrolling a student runs six checks, in this order:

| # | Rule | Error code | HTTP |
|---|---|---|---|
| 1 | Student must exist | `RESOURCE_NOT_FOUND` | 404 |
| 2 | Student must be `ACTIVE` | `STUDENT_NOT_ELIGIBLE` | 409 |
| 3 | Enrollment window must be open | `ENROLLMENT_WINDOW_CLOSED` | 409 |
| 4 | No existing enrollment | `DUPLICATE_RESOURCE` | 409 |
| 5 | Course must have a free seat | `COURSE_FULL` | 409 |
| 6 | All prerequisites passed | `PREREQUISITES_NOT_MET` | 409 |

The **ordering is deliberate**, and it is a trade-off rather than a simple
rule. Student eligibility is checked first because it is cheap and needs no
lock: there is no point serialising every request on the course row just to
discover the student was suspended.

The row lock is then taken *before* the remaining checks (look at
`EnrollmentService.enroll` — it is step 2 in the code, not step 5), because the
`Course` object those checks need is the very row that has to be locked. Loading
it unlocked and locking it later would mean reading it twice, and acting on the
first, stale copy in between. The cost is that the window, duplicate and
prerequisite checks all run while holding the lock; the benefit is that the
capacity check is trustworthy. Moving the cheap checks inside or outside a lock
is exactly the kind of judgement call worth being able to argue about.

**The concurrency detail worth understanding.** Rule 5 cannot be made correct by
checking then inserting — two requests can both pass the check before either
commits, and the course ends up over capacity. Neither optimistic locking (no
row was modified) nor a unique constraint (different students) helps. The fix is
a `SELECT ... FOR UPDATE` on the *course* row, making it the serialisation point
for its own capacity. See `CourseRepository.findByIdWithPessimisticLock`.

---

## Walkthrough

The database is seeded on first startup with 3 professors, 6 courses and 4
students.

**First, resolve the ids.** Do not guess them, and do not assume the first
student is `1`. Every entity draws its id from one shared sequence with
`allocationSize = 50` (see `BaseEntity`), so a freshly seeded database numbers
the professors 1–3, the courses 52–57 and the students 102–105. Those gaps are
deliberate — one round trip to the sequence buys fifty ids — and they are the
reason the stable handles are the *student number* and the *course code*, never
the surrogate id:

```bash
BASE=http://localhost:8280/enrollment/api

# Students have a lookup by their business key
LUCA=$(curl -s $BASE/students/by-number/100001 | grep -o '"id":[0-9]*' | cut -d: -f2)

# Courses do not, so pick the id out of the open-courses list by code
by_code() { curl -s $BASE/courses/open | tr '}' '\n' \
            | grep "\"code\":\"$1\"" | grep -o '"id":[0-9]*' | cut -d: -f2; }
CS101=$(by_code CS101)
```

> Adding `GET /courses/by-code/{code}`, to match `students/by-number`, is a good
> first exercise: one repository method, one resource method, one test.

```bash
# What can I sign up for?
curl -s $BASE/courses/open

# Enrol Luca in CS101 Programming Fundamentals
curl -s -X POST $BASE/enrollments \
     -H 'Content-Type: application/json' \
     -d "{\"studentId\":$LUCA,\"courseId\":$CS101}"

# Keep the enrollment id the response just handed back
ENR=$(curl -s $BASE/students/$LUCA/enrollments | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)

# Record a pass with honours
curl -s -X POST $BASE/enrollments/$ENR/grade \
     -H 'Content-Type: application/json' \
     -d '{"grade":30,"withHonours":true}'

# The transcript, with earned credits and weighted average
curl -s $BASE/students/$LUCA
```

### Watch the rules fire

```bash
SOFIA=$(curl -s $BASE/students/by-number/100002 | grep -o '"id":[0-9]*' | cut -d: -f2)
CHIARA=$(curl -s $BASE/students/by-number/100004 | grep -o '"id":[0-9]*' | cut -d: -f2)
CS401=$(by_code CS401)
# CS150 ran last academic year, so it is NOT in /courses/open
CS150=$(curl -s "$BASE/courses?year=2024" | grep -o '"id":[0-9]*' | cut -d: -f2)

# PREREQUISITES_NOT_MET — CS401 requires CS101 and CS301
curl -i -X POST $BASE/enrollments -H 'Content-Type: application/json' \
     -d "{\"studentId\":$SOFIA,\"courseId\":$CS401}"

# STUDENT_NOT_ELIGIBLE — Chiara (100004) is suspended
curl -i -X POST $BASE/enrollments -H 'Content-Type: application/json' \
     -d "{\"studentId\":$CHIARA,\"courseId\":$CS101}"

# ENROLLMENT_WINDOW_CLOSED — CS150 closed last year
curl -i -X POST $BASE/enrollments -H 'Content-Type: application/json' \
     -d "{\"studentId\":$LUCA,\"courseId\":$CS150}"

# INVALID_GRADE — lode requires exactly 30. Use a fresh, still-ACTIVE
# enrollment: the state machine is checked before the grade is, so retrying
# this on the COMPLETED one above reports ILLEGAL_STATE_TRANSITION instead.
CS201=$(by_code CS201)
ENR2=$(curl -s -X POST $BASE/enrollments -H 'Content-Type: application/json' \
       -d "{\"studentId\":$LUCA,\"courseId\":$CS201}" \
       | grep -o '"id":[0-9]*' | cut -d: -f2)
curl -i -X POST $BASE/enrollments/$ENR2/grade -H 'Content-Type: application/json' \
     -d '{"grade":28,"withHonours":true}'

# VALIDATION_FAILED — every bad field reported at once
curl -i -X POST $BASE/students -H 'Content-Type: application/json' \
     -d '{"studentNumber":"12AB","firstName":"","email":"nope","enrollmentYear":1800}'
```

> Running all of these in order, with the assertions written out, is exactly what
> [`scripts/api-tour.sh`](scripts/api-tour.sh) does — see [Checking it still
> works](#checking-it-still-works) below.

Every error comes back as **RFC 7807 Problem Details**:

```json
{
  "type": "https://api.unicam.it/problems/business-rule-violation",
  "title": "Business Rule Violation",
  "status": 409,
  "detail": "Course CS401 has reached its capacity of 3 students",
  "errorCode": "COURSE_FULL",
  "correlationId": "a3f9c2e1",
  "instance": "/enrollments",
  "timestamp": "2026-08-10T14:32:07.412Z"
}
```

That `correlationId` also appears in the `X-Correlation-Id` response header and
on **every server-side log line** for the request. Grep for it:

```bash
docker compose logs wildfly | grep a3f9c2e1
```

---

### Checking it still works

```bash
docker compose down -v && docker compose up -d   # a fresh, seeded database
./scripts/api-tour.sh
```

`scripts/api-tour.sh` walks every endpoint and every business rule and
**asserts** what each should return — 41 checks, green or red. Run it after any
change you make; it catches the breakage that compiling cleanly does not.

It needs a freshly seeded database, because several checks depend on the
starting state (nobody enrolled yet, MA101 at its original capacity). It also
resolves the seeded ids at runtime rather than hard-coding them, for the reason
described under [Walkthrough](#walkthrough) — worth reading as a small example
of a test that cannot drift out of date.

---

## Testing

```bash
mvn test      # 35 unit tests — no database, no Docker, ~4 seconds
mvn verify    # + 25 integration tests against in-memory H2
```

The split is by filename, and it is a convention worth keeping:

- `*Test` → Surefire → **unit tests.** One class under test, everything else
  mocked. Fast enough to run constantly.
- `*IT` → Failsafe → **integration tests.** Real JPA against real SQL. These
  catch the bugs unit tests structurally cannot: JPQL typos, wrong pagination,
  a `GROUP BY` that silently omits rows.

> **Note on H2.** It starts in milliseconds and needs no Docker, which is why
> repository tests can run on every build. But H2 is *not* PostgreSQL — dialects,
> locking and error messages all differ. The professional next step is
> **Testcontainers**, which starts a real PostgreSQL for the test run. Every
> concept in these tests carries over unchanged.

---

## Suggested reading order

Read the code in this order and each file will explain the next.

1. **`pom.xml`** — what the project is and what it depends on. Note `provided` scope.
2. **`domain/model/BaseEntity.java`** — surrogate keys, `@Version`, why `equals` is hard.
3. **`domain/model/Course.java`** — every relationship type in one file.
4. **`domain/model/Enrollment.java`** — the association entity and its state machine.
5. **`repository/AbstractJpaRepository.java`** — `persist` vs `merge`, locking, Criteria.
6. **`repository/StudentRepository.java`** — named queries vs dynamic Criteria queries.
7. **`service/EnrollmentService.java`** — ⭐ *the heart of the application.*
8. **`api/rest/EnrollmentResource.java`** — how little a resource should do.
9. **`api/exception/*Mapper.java`** — errors handled in one place, not fifty.
10. **`common/logging/LoggingInterceptor.java`** — AOP, and how `@Transactional` works.
11. **`config/DataSeeder.java`** — idempotence, and the self-invocation trap.
12. **`src/test/…`** — how each of the above is proven.

Then: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the layer-by-layer
rationale, and [`docs/GLOSSARY.md`](docs/GLOSSARY.md) for the vocabulary.

---

## Then stop reading and start writing

Reading correct code builds recognition; writing it builds recall. Five ways to
practise, roughly in the order worth doing them:

| | |
|---|---|
| **[docs/EXERCISES.md](docs/EXERCISES.md)** | Four stubs and 31 failing tests that specify them: a JPQL query, a domain rule with exacting boundaries, an atomic transfer use case, and the endpoint that exposes it. `mvn test -Pexercises` |
| **[docs/BREAKING.md](docs/BREAKING.md)** | `./scripts/break.sh` introduces one classic bug at a time — a broken fetch plan, a silent N+1, a missing row lock, a test that lies — and puts it back. |
| **[docs/DEBUGGING.md](docs/DEBUGGING.md)** | Attach to WildFly on port 8787 and step over the closing brace of `enroll()` to watch entities detach. |
| **[The fieldbook](src/main/webapp/tutorial.html)** | 20 chapters at `/enrollment/tutorial.html`, from the language contracts underneath Java to the deployment sequence above it — with ten new hands-on labs among them: a lost-update race, a HashMap bucket visualiser, a lazy-stream stepper, an entity-state explorer, a cascade explorer and a connection-pool simulator. |
| **The fieldbook's Cheat sheet** | 94 golden rules, grouped by chapter. Each reason is hidden until you tap it, and the page prints as a revision sheet. |
| **The fieldbook's Self-test** | 60 retrieval-practice questions. Answer before revealing. |

The exercise tests are excluded from the normal build, so `mvn verify` stays an
honest 60/60 signal about the application itself.

---

## Troubleshooting

**Deployment failed.** Read the marker file — it contains the reason:

```bash
cat docker/deployments/enrollment.war.failed
docker compose logs wildfly --tail 100
```

**Endpoints 404 or 500 at random, and work again a second later.** The server is
redeploying itself in a loop. Confirm it:

```bash
docker compose logs wildfly --since 60s | grep -cE 'Redeployed|Replaced deployment'
```

Zero is correct when you have not just run `mvn package`. Anything around ten
means the deployment scanner is in the timestamp loop described under
[The development loop](#the-development-loop) — rebuild the image so the
`configure.cli` setting is applied: `docker compose up -d --build`.

**`mvn` not recognised.** Open a new terminal (the PATH change applies to new
sessions), or run `& "$env:USERPROFILE\tools\apache-maven-3.9.9\bin\mvn.cmd" verify`.

**Port already in use.** Change the host side of the mapping in
`docker-compose.yml`, e.g. `"8380:8080"`. Only the left-hand number matters;
nothing inside the application refers to it.

**Start completely fresh.**

```bash
docker compose down -v      # -v also deletes the database volume
mvn clean verify
docker compose up -d --build
```

**Inspect the schema Hibernate generated.** Connect any SQL client to
`localhost:55433` (`enrollment`/`enrollment`/`enrollment`) — looking at the real
tables is the fastest way to understand a mapping.

---

## Two bugs this project hit while being built

Both are worth reading, because they are the kind you meet in real work and
neither is caught by a naive test.

**1. `LazyInitializationException` on a detached entity.**
`GET /courses/{id}` returned a 500: the query fetch-joined the course's
*prerequisites* (what the enrollment rule needs) but not its *professor* (what
the response mapper needs). Nothing failed inside the transaction, because a
lazy load just fires another SELECT while the persistence context is open — it
only broke once the entity was detached and the mapper touched it.

*The lesson:* a fetch plan is a contract between a query and **every** consumer
of its result. Add a consumer that touches one more association and the query
must change too. `CourseRepositoryIT` now calls `entityManager.clear()` before
asserting, so tests run under the same detached conditions as the REST layer —
without that line the test passes whether or not the association was fetched.

**2. `setMaxResults` on a collection fetch join.**
The shared `singleResult()` helper capped results at 2 rows as a safety measure.
That is silently wrong when the query fetch-joins a *collection*: SQL returns one
row per child, so a `LIMIT` truncates the collection rather than the results. It
threw in production and passed in tests, because the test persistence unit did
not have `fail_on_pagination_over_collection_fetch` enabled the way the real one
did.

*The lesson:* a "safety" measure can introduce a bug, and a test configuration
that differs from production will happily prove the wrong thing. The test
`persistence.xml` now mirrors the production settings that affect behaviour.

---

## Known limitations

Stated openly, because a study project that pretends to be production-ready
teaches the wrong lesson.

- **No authentication.** Every endpoint is public. Real systems add
  `@RolesAllowed` plus JWT or OIDC.
- **`hbm2ddl.auto=update`** generates the schema. Convenient for learning,
  forbidden in production — use **Flyway** or **Liquibase**.
- **Only direct prerequisite cycles are detected.** A full cycle check (A→B→C→A)
  needs a graph traversal.
- **The scheduled job is not cluster-safe.** Two nodes would both run it. Real
  clusters need a distributed lock or an external scheduler.
- **No API versioning.** A real public API would be at `/api/v1`.
- **Hard-coded credentials** in `configure.cli` and `docker-compose.yml`, which
  is acceptable only because this never leaves your machine.

Each of these is a good next exercise.
