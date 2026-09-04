# Architecture

How this application is put together, and why each decision was made that way.

---

## 1. The layers

```
                  HTTP request
                       │
   ┌───────────────────▼────────────────────┐
   │  API LAYER          api/               │   Knows about HTTP.
   │  resources · DTOs · mappers            │   Knows nothing about SQL.
   │  exception mappers · filters           │
   └───────────────────┬────────────────────┘
                       │  commands in, entities out
   ┌───────────────────▼────────────────────┐
   │  APPLICATION LAYER  service/           │   Owns transactions.
   │  use cases · business rules            │   Owns cross-entity rules.
   │  transactions · domain events          │   Knows nothing about HTTP.
   └───────────────────┬────────────────────┘
                       │  entities in, entities out
   ┌───────────────────▼────────────────────┐
   │  PERSISTENCE LAYER  repository/        │   Owns queries.
   │  queries · fetch plans · locking       │   Owns NO business rules.
   └───────────────────┬────────────────────┘
                       │
   ┌───────────────────▼────────────────────┐
   │  DOMAIN LAYER       domain/            │   Owns the rules that are
   │  entities · value objects · enums      │   ALWAYS true.
   │  events · custom constraints           │   Depends on nothing of ours.
   └────────────────────────────────────────┘
```

**The dependency rule: arrows point inward, never outward.**

`api` imports `service`. `service` imports `repository` and `domain`. `domain`
imports neither. You can delete the entire `api` package and the domain still
compiles — that is the test of whether the layering is real.

This is why `service` takes a `CreateStudentCommand` rather than the REST layer's
`CreateStudentRequest`. Accepting the DTO would point the arrow the wrong way and
make the service unusable from a scheduled job or a message consumer.

---

## 2. What lives where, and why

### Domain layer — `domain/`

The only layer with no framework dependencies beyond JPA and Bean Validation
annotations. Plain Java, testable with plain JUnit.

Rules that are **always** true about a single object live here:

- `EnrollmentStatus` owns the legal transition table.
- `Enrollment.recordPass()` refuses a grade outside 18–30, and refuses *lode*
  below 30.
- `Course.isEnrollmentOpen(now)` owns the window comparison.
- `Email.of()` refuses a malformed address and normalises case.

This is a **rich domain model**. The opposite — entities that are nothing but
getters and setters, with all logic in services — is called an **anemic domain
model**, and it is an anti-pattern for a specific, practical reason: when a rule
lives in a service, nothing stops the *next* service from forgetting it. When it
lives on the entity, the rule cannot be bypassed.

> **Where's the line?** A rule about **one** object goes on that object. A rule
> spanning **several** objects, or needing a query, goes in the service. "Can
> this student enrol in this course?" needs both entities plus a seat count —
> that is a service rule.

### Persistence layer — `repository/`

One responsibility: turn a question into a query.

Every repository extends `AbstractJpaRepository<T>`, which provides `save`,
`findById`, `delete`, `count` and paginated `findAll`. Subclasses add the queries
their aggregate needs.

Two query styles, chosen deliberately:

| Style | Use when | Example |
|---|---|---|
| **JPQL** as `@NamedQuery` | The query shape is fixed | `Student.FIND_BY_STUDENT_NUMBER` |
| **Criteria API** | The shape depends on which filters were supplied | `StudentRepository.search` |

Named queries are parsed and validated at **deploy time**, so a typo fails the
deployment rather than a user's request. The Criteria API is verbose but
type-safe and composable — and building JPQL by string concatenation instead is
how both SQL injection and unmaintainable code happen.

### Application layer — `service/`

Owns the **transaction boundary**. One business operation, one transaction,
marked with `@Transactional`.

Also owns:
- rules spanning multiple entities (prerequisites, capacity);
- translation of domain exceptions into the application's vocabulary;
- publication of domain events.

Never owns: HTTP status codes, `Response` objects, request headers.

### API layer — `api/`

Three jobs and no others:

1. HTTP → application (DTO to command, path param to id)
2. call exactly one service method
3. application → HTTP (entity to DTO, choose status, set headers)

If you find an `if` in a resource class that decides something about the domain,
it is in the wrong file.

---

## 3. A request, end to end

`POST /api/enrollments` with `{"studentId": 1, "courseId": 4}`:

```
 1. CorrelationIdFilter            generates "a3f9c2e1", puts it in the MDC
 2. JSON-B                         deserialises the body into EnrollRequest
 3. Bean Validation (@Valid)       checks @NotNull, @Positive → 400 if bad
 4. LoggingInterceptor             starts the timer
 5. @Transactional                 the container BEGINS a JTA transaction
 6. EnrollmentService.enroll()
      ├─ studentRepository.findById          SELECT
      ├─ student.canEnroll()                 domain rule
      ├─ courseRepository …PessimisticLock   SELECT … FOR UPDATE   ← serialises here
      ├─ course.isEnrollmentOpen(now)        domain rule, injected Clock
      ├─ enrollmentRepository.findByStudentAndCourse   SELECT
      ├─ enrollmentRepository.countOccupiedSeats       SELECT COUNT(*)
      ├─ verifyPrerequisites                 SELECT per prerequisite
      ├─ Enrollment.create(…)                domain factory
      ├─ repository.save + flush             INSERT  (unique constraint enforced)
      └─ event.fire(EnrollmentCreatedEvent)
             ├─ auditEnrollmentCreated       IN_PROGRESS — inside the transaction
             └─ onEnrollmentCreated          AFTER_SUCCESS — deferred
 7. @Transactional                 COMMIT  → the lock is released here
 8.                                the AFTER_SUCCESS observer now runs
 9. LoggingInterceptor             logs the elapsed time
10. EnrollmentMapper               entity → EnrollmentResponse
11. JAX-RS                         201 Created + Location header
12. CorrelationIdFilter            adds X-Correlation-Id, CLEARS the MDC
```

Any exception thrown in step 6 skips straight to an exception mapper, and the
transaction rolls back.

---

## 4. The decisions worth defending

### Why DTOs instead of exposing entities

Four independent reasons, any one of which would be sufficient:

1. **Mass assignment.** If the entity is the request body, a caller can send
   `{"id":1,"version":99,"status":"GRADUATED"}`. That is a security
   vulnerability, not a style preference.
2. **Coupling.** Your database schema becomes your public API contract.
3. **Different shapes.** Creating needs no id; the response must have one.
4. **Lazy loading.** Serialising an entity walks its associations and either
   fires a cascade of queries or throws `LazyInitializationException`.

### Why every association is `LAZY`

To-one associations default to `EAGER` in the JPA specification, which is a
genuine design mistake: loading a `Course` would always load its `Professor`,
whether you need it or not, and those eager loads compound across a graph.

Make everything lazy, then declare a **fetch plan per use case** with
`JOIN FETCH`. Every repository method in this project states which associations
it loads, and every mapper documents which it requires.

The two tempting shortcuts are both traps:
- making associations eager punishes every other query;
- keeping the transaction open across rendering ("Open Session In View") hides
  N+1 problems and holds database resources during I/O.

### Why the clock is injected

`Instant.now()` inside business logic is an untestable dependency. With an
injected `Clock`, a test can assert behaviour one nanosecond before and after a
deadline. This generalises to every non-deterministic input: time, randomness,
UUID generation, the file system.

### Why the exception hierarchy is unchecked

In JTA, an **unchecked** exception thrown out of a `@Transactional` method rolls
the transaction back. A **checked** exception does *not* — the container assumes
you handled it and **commits**. That default has quietly corrupted a lot of data
over the years.

### Why business rules are enforced twice

The duplicate-enrollment rule is checked in the service *and* backed by a unique
constraint in the database. This is not redundancy:

- the service check produces a good error message in the common case;
- the constraint is the actual guarantee, because between the service's check and
  its insert another transaction can slip in.

Only the database sees all transactions. Any rule that must never be violated
needs a real constraint behind it.

---

## 5. Concurrency

Three distinct mechanisms, used for three distinct problems.

| Mechanism | Where | Protects against |
|---|---|---|
| **Optimistic lock** (`@Version`) | Every entity | Lost updates — two users editing the same row |
| **Pessimistic lock** (`FOR UPDATE`) | `Course` during enrollment | Two enrollments both seeing the same free seat |
| **Unique constraint** | `(student_id, course_id)` | Double enrollment, definitively |

**Why optimistic locking is not enough for capacity.** Two concurrent
enrollments do not modify the course row at all — they insert into
`enrollments`. No version changes, so nothing is detected. And the unique
constraint does not help either, because these are two *different* students.

The course row is therefore used as an explicit **serialisation point** for its
own capacity: take a write lock on it, and the second transaction waits until the
first commits and sees the true count. Recognising when you need a serialisation
point — and which row should be it — is a genuinely useful skill.

---

## 6. Error handling

```
 domain      throws IllegalStateException / IllegalArgumentException
                    │   (plain JDK types — the domain has no framework deps)
                    ▼
 service     catches, rethrows as BusinessRuleViolationException
                    │   (with a stable machine-readable errorCode)
                    ▼
 api         ExceptionMapper → RFC 7807 ProblemDetail + HTTP status
```

Each layer speaks its own vocabulary, with explicit translation at the boundary.

| Exception | Status | Meaning to the client |
|---|---|---|
| `ConstraintViolationException` | 400 | A field failed validation — fix it |
| `InvalidRequestException` | 400 | A parameter we parse by hand is malformed |
| `ResourceNotFoundException` | 404 | No such thing |
| `BusinessRuleViolationException` | 409 | Your request was fine; the state forbids it |
| `DuplicateResourceException` | 409 | It already exists |
| `OptimisticLockException` | 409 | Someone else changed it; reload and retry |
| anything else | 500 | Logged in full server-side; you get a correlation id |

The 400-versus-409 distinction is what makes an API pleasant to integrate
against. A 400 says *fix your request*; a 409 says *your request was fine, try
later or choose something else*.

**What never appears in an error response:** stack traces, SQL, class names, file
paths. Error bodies cross a trust boundary. Log the detail, return the reference.

---

## 7. Cross-cutting concerns

| Concern | Mechanism | File |
|---|---|---|
| Method timing & tracing | CDI interceptor | `common/logging/LoggingInterceptor` |
| Per-class loggers | CDI producer + `InjectionPoint` | `common/logging/LoggerProducer` |
| Testable time | CDI producer | `common/ClockProducer` |
| Request correlation | JAX-RS filter + SLF4J MDC | `api/filter/CorrelationIdFilter` |
| Error rendering | JAX-RS exception mappers | `api/exception/` |
| Startup seeding | `@Startup @Singleton` EJB | `config/ApplicationBootstrap` |
| Scheduled work | EJB timer (`@Schedule`) | `service/EnrollmentMaintenanceJob` |

### Interceptor ordering

`@Priority` both enables an interceptor and places it in the chain. Lower runs
further out.

```
PLATFORM_BEFORE  =    0     security
LIBRARY_BEFORE   = 1000
APPLICATION      = 2000  ←  LoggingInterceptor sits at 2010
LIBRARY_AFTER    = 3000
PLATFORM_AFTER   = 4000     transactions
```

Because `@Transactional` lives in the `PLATFORM_AFTER` band, our interceptor runs
*outside* the transaction — so the duration it measures **includes commit time**.
That is usually what you want, since commit is often where the time actually goes.

### The self-invocation trap

Interceptors work by generating a **proxy**. A `@Transactional` method calling
another `@Transactional` method **on the same object** does not start a new
transaction, because the call never leaves the instance and no proxy is involved.

Same reason `@Transactional` on a `@PostConstruct` of a normal-scoped CDI bean
silently runs with no transaction. `DataSeeder` is called from
`ApplicationBootstrap` precisely to cross a bean boundary — see the comment
there.

This trap is identical in Spring, and it is asked about in interviews constantly.

---

## 7b. The second bounded context

`it.unicam.cs.enrollment.fieldbook` is a separate model in the same deployable:
the accounts, study progress and sticky notes behind the fieldbook page. It
repeats the same four layers, with its own `domain/`, `repository/`, `service/`
and `api/`, plus a `security/` package.

It is separate on purpose. A learner is not a student, even when they are the
same person — one has a matriculation number and a registrar, the other has a
password and a reading streak. The two share only `BaseEntity`, `Email` and
`AbstractJpaRepository`: a deliberately tiny **shared kernel**. No foreign key
crosses between the two groups of tables and no query joins them, which is
precisely what would make this splittable into two deployables later.

It is also where authentication actually exists in this codebase, so it is the
place to read after fieldbook chapter 15. The design decisions, the trade-offs
each one cost, and the list of what is still missing are in
[ACCOUNTS.md](ACCOUNTS.md).

---

## 7c. The mailing system

`it.unicam.cs.enrollment.mail` sends the confirmation and exam-result emails. It
is worth reading as a unit, because it is the one part of this codebase that has
to reconcile a database transaction with something outside the database — and
that is a genuinely hard problem with a standard answer.

### The shape

```
EnrollmentService.enroll()                        [transaction begins]
   └─ fires EnrollmentCreatedEvent
        └─ EnrollmentMailListener  (@Observes, IN_PROGRESS)
             └─ MailService.enqueue()  ──► INSERT INTO mail_outbox
                                                   [transaction commits]

MailDispatcher  (@Schedule, every 30s)
   ├─ [tx] find due ids
   ├─ [tx] claim one: PENDING → SENDING
   │   ...  transport.send()   ← no transaction open
   └─ [tx] record outcome: SENT, or PENDING with a backoff, or DEAD
```

### Why the row, and not a `Transport.send` in the service

Sending inside the transaction gives you two failure modes and no way to avoid
both: commit first and the process can die before the email is sent (lost mail),
or send first and the transaction can roll back (an email about an enrollment
that does not exist). Writing the message to a table *in the same transaction*
as the enrollment removes the choice — the row and the seat are one atomic fact
— and moves delivery to a process that can retry. This is the **transactional
outbox** pattern, and the same shape solves the same problem for webhooks and
for publishing to a broker.

The cost is honest and worth stating: delivery becomes **at-least-once**. A
crash between "the server accepted it" and "we wrote that down" sends a
duplicate. That is a deliberate choice of which way to fail, and the right one
for a confirmation email.

### The four decisions to look at

| Decision | Where | Why |
|---|---|---|
| Observer runs `IN_PROGRESS`, not `AFTER_SUCCESS` | `EnrollmentMailListener` | It only writes a row, so it *should* be atomic with the enrollment. The usual advice applies to observers with external side effects — compare `EnrollmentNotificationListener`, which defers |
| Claim and send are in different beans | `OutboxProcessor` / `MailDispatcher` | Self-invocation bypasses the proxy, so `@Transactional(REQUIRES_NEW)` on a method called via `this` does nothing at all. See §7's self-invocation trap — this is that trap, avoided on purpose |
| No transaction across the network call | `MailDispatcher.dispatchOne` | A transaction holds a pooled connection. One slow relay would otherwise drain the pool and take the whole application down |
| Transport is an interface with two implementations | `mail/transport/` | Log-only lets the whole pipeline run with no mail server; SMTP is selected by config, and a producer method picks between them at startup because the choice depends on whether a JNDI session exists |

### Running it

`docker compose up -d` now also starts **Mailpit**, a fake SMTP server with a
web inbox at <http://localhost:8225>. Every message the application sends lands
there and goes no further. Without it — a native WildFly, say — the transport
falls back to writing the rendered email into the server log, says so at WARN,
and reports itself as `log only` at `GET /api/mail/status`.

The operational endpoints are under `/api/mail` and require a signed-in
fieldbook account (the outbox contains addresses and message bodies, so it is
not public). See [ACCOUNTS.md](ACCOUNTS.md) for the session mechanics.

---

## 8. What a production version would add

| Gap | What you would use |
|---|---|
| Schema management | Flyway or Liquibase instead of `hbm2ddl.auto` |
| Authentication *(enrollment API)* | `@RolesAllowed` + JWT / OIDC (Keycloak). Already done for `/api/fieldbook/**` — see [ACCOUNTS.md](ACCOUNTS.md) |
| API documentation | MicroProfile OpenAPI → live Swagger UI |
| Health & metrics | MicroProfile Health + Metrics → Prometheus |
| Resilience | MicroProfile Fault Tolerance (retry, circuit breaker) |
| Real integration tests | Testcontainers + Arquillian or REST Assured |
| Distributed tracing | OpenTelemetry (the correlation ID generalises into this) |
| Caching | JPA second-level cache, or Redis for read-heavy paths |
| API versioning | `/api/v1`, plus a deprecation policy |
| Secrets | Vault / cloud secret manager, never a file in git |
| Cluster-safe scheduling | Distributed lock, Quartz clustered, or a K8s CronJob |
| Cluster-safe outbox claim | `SELECT … FOR UPDATE SKIP LOCKED`, or a broker that hands each message to one consumer |
| Mail bounces and complaints | A provider webhook (SES / SendGrid) feeding back into `mail_outbox`, plus a suppression list |
| Mail deliverability | SPF, DKIM and DMARC records for the sending domain — without them a correctly built sender still lands in spam |

Each is a reasonable next exercise, and each maps onto a concept already present
here.
