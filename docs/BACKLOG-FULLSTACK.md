# Backlog — the full-stack Java pivot

Audit date: 2026-09-04. Sources:

1. A junior "Java Developer — Data & Analytics" advert (microservices, Spring
   Boot, REST, SQL + NoSQL, JS/Angular, Git, cloud) — sections A–C.
2. A graduate-academy advert (microservices, Angular, API, container, cloud,
   blockchain, AI/ML, mobile, IoT, certifications) — section D.

The course was 41 chapters built around one Jakarta EE application on WildFly.
It answered the adverts' *ideas* well and their *stack* only on paper.

It is now **47 chapters across four runnable projects** — the Jakarta EE WAR, a
Spring Boot service, a notification service and an Angular client. Chapters
42–47 are a new part, *The full-stack extension*, appended rather than
interleaved so that no existing cross-reference had to be renumbered (see
section C).

This file records what was missing, what has been closed, and what has not.

---

## A. The advert, line by line

### Main responsibilities

| Line from the advert | Verdict | Where it is now |
|---|---|---|
| Translate requirements into solutions with design/dev teams | **covered** | ch. 22 Agile · 24 English · 34 Reading code |
| Scalable microservices-based applications | **partial — concepts only** | ch. 33 gives boundaries, sagas, pods. No service is ever built or called. |
| …using modern technologies (e.g. **Spring Boot**) | **partial — paper only** | ch. 16–18 teach Spring properly, but `grep springframework pom.xml` returns nothing. Nothing compiles, nothing runs, no test proves it. |
| Design, develop and **optimize REST APIs** | **covered** | ch. 12 JAX-RS · 13 The boundary · 25 Performance. Real code, real error contract, RFC 7807, correlation IDs. |
| Communication between **distributed services** | **missing** | No service-to-service call anywhere: no client, no timeout, no retry, no fallback. |
| Innovation aligned with **Data & Analytics** trends | **missing** | The domain framing is absent. No reporting/aggregation chapter, no batch, no analytical query beyond ch. 07's `GROUP BY`. |

### Essential requirements

| Line from the advert | Verdict | Where it is now |
|---|---|---|
| Degree in a data-centric discipline | n/a | not a course topic |
| Fundamentals of **OOP** | **covered** | ch. 04 Java laws · 31 SOLID and patterns |
| Familiarity with **Java** | **covered** | ch. 04–06, 30 (Java 8→21) |
| **Relational** DBs (PostgreSQL, MySQL) | **covered, strong** | ch. 07 (keys, joins, indexes, ACID, four dialects) · 08 · 09 · 29 migrations |
| **NoSQL** (MongoDB, DynamoDB) | **missing in practice** | One paragraph in ch. 33 plus one flashcard. `MongoDB` appears once, inside an auto-config log line. `DynamoDB`: zero. No document modelling, no query, no code. |
| Team-oriented mindset | **covered** | ch. 22 Agile · 24 English · 34 Reading code |

### Nice to have

| Line from the advert | Verdict | Where it is now |
|---|---|---|
| Internships / relevant projects | **covered** | ch. 37 The CV and the repository — this project *is* the answer |
| **JavaScript and/or Angular** | **partial — React yes, Angular no** | ch. 32 builds a real React component against this API. Angular is a six-row translation table. TypeScript is one `interface`. No Angular app, no RxJS beyond one word, no CLI, no `HttpClient` call. |
| **Git** | **covered, strong** | ch. 19 (three trees, merge vs rebase, conflicts, undo table, team flow) |
| **Cloud** (GCP, AWS) | **partial — one paragraph** | ch. 33 covers managed service / object storage / IAM as ideas. `AWS` and `GCP` appear three times each. Nothing deployed to either. ch. 27 and the Coolify/Hetzner docs are the real deployment story, and they are single-VPS Docker. |

---

## B. What to add

Ranked by what actually changes a junior's employability against this advert.

### B1 — Spring Boot must stop being a reading exercise · **DONE (code), 2026-09-05**

The advert names Spring Boot explicitly. Chapters 16–18 were good prose about a
framework the repository did not contain. Built as `spring-service/` — an
independent Maven project in this repository, so the root WAR build is
untouched. See [spring-service/README.md](../spring-service/README.md).

- [x] A second Maven module — `spring-service/` — a real Spring Boot app against
      the **same PostgreSQL schema**. Same entities, same rules. Spring Boot
      3.5.0, Java 21, port 8281.
- [x] Port endpoints exactly: `GET /api/courses`, `/courses/open`,
      `/courses/{id}`, `/courses/{id}/enrollments`, `POST /api/enrollments`,
      `POST /api/enrollments/{id}/withdrawal`. Same paths under `/api`, same
      JSON field names, so a client cannot tell the two services apart.
- [x] `@ControllerAdvice` producing the *same* RFC 7807 body as the six JAX-RS
      exception mappers — same `type` URIs, same `errorCode` values, same
      `correlationId` extension field. Pinned by assertions in
      `CourseControllerTest`.
- [x] Spring Data JPA repository next to the hand-written one: derived queries,
      `@Query` with JOIN FETCH, `@Lock(PESSIMISTIC_WRITE)`, and paging with an
      explicit `countQuery`.
- [x] `@SpringBootTest` + `@DataJpaTest` + `@WebMvcTest` + Testcontainers.
      37 unit/slice tests green with no Docker; 11 integration tests that run
      the **real Flyway migrations** and skip cleanly when Docker is absent.
- [x] Actuator with liveness/readiness split, and `/actuator/env` deliberately
      not exposed. Ch. 33 finally has an endpoint to point at.

**Still open on B1:**

- [ ] Run `mvn verify -f spring-service/pom.xml` on a machine with a working
      Docker daemon. The 11 integration tests compile and skip correctly but
      have **never been executed** — the daemon was unresponsive on the machine
      they were written on. Until that passes, the "same schema" claim rests on
      a manual column-by-column reading of `V1__baseline_schema.sql`, not on
      `ddl-auto: validate`. This is the highest-value thing to do next.
- [ ] Watch for a Hibernate version difference: WildFly 41 ships Hibernate
      7.4.5, Spring Boot 3.5.0 ships 6.6.x. Both should map
      `@Enumerated(STRING)` to `varchar` and `Instant` to
      `timestamp with time zone` on PostgreSQL, but only `validate` against the
      real schema proves it.
- [x] **Chapter 42 written** — `Spring, running`, in the new part *The
      full-stack extension*. Chapters 16–18 now have a running counterpart.

### B2 — NoSQL, for real · **DONE, 2026-09-05**

Built in `spring-service/.../document/`, plus a `mongo` service in
`docker-compose.yml`.

- [x] Document modelling against a relational mind, written out on
      `CourseDocument`: embed vs reference and the rule for choosing,
      denormalisation as a stated cost, no foreign keys, flexible-is-not-absent,
      and the 16 MB limit as a design constraint.
- [x] MongoDB in `docker-compose.yml` (port 27117), and a Spring Data MongoDB
      read model — `CourseProjectionService` projects PostgreSQL rows into
      documents, one direction only.
- [x] The interview answer made demonstrable rather than asserted:
      `thereAreNoForeignKeys()` deletes a course from PostgreSQL and shows the
      document surviving, and `CourseDocumentRepository` states plainly that the
      seat-counting rule has no simple correct equivalent on a document store.
- [x] **Nine tests against a REAL mongod**, via flapdoodle embedded MongoDB —
      no Docker needed, so they run in the ordinary `mvn test` loop.
- [x] Mongo is **opt-in via a profile**, and that is the architectural point: a
      read model being unavailable must not stop the service taking enrollments.

**Found while doing this** — a genuine Spring Data MongoDB trap, now documented
at length on `CourseDocument.fillRate`. A `BigDecimal` is stored **as a String**
by default, so `{ $lt: 25.0 }` returns zero documents, always, with no error.
Switching to `@Field(targetType = DECIMAL128)` then fails the *query* instead,
because a `?0` placeholder in a JSON filter binds as a string. The resolution was
to ask what the value is: a percentage in a derived read model is a `double`, and
BigDecimal earns its place for money and for anything summed.

- [x] **Chapter 44 written** — `NoSQL`.

**Still open on B2:**

- [ ] DynamoDB at vocabulary level: partition key vs sort key, single-table
      design, why it is not "MongoDB on AWS". One page, zero code — the only
      part of B2 not done.

### B3 — Distributed communication · **DONE, 2026-09-05**

Everything ch. 33 promises and never shows, now built as `notification-service/`
— a third independent Maven project — plus the client side in `spring-service`.

- [x] The notification listener extracted into a second deployable: the exact
      cut ch. 33 marks "a good cut", ~90 lines of application code.
- [x] A real HTTP client (`RestClient`) with **two** timeouts — connect and read
      — and a written argument for why a connect timeout alone leaves you exposed
      to the worse failure.
- [x] Idempotency on the consumer, keyed on an `eventId` the SENDER generates
      once and reuses on every retry. Deduplication is a bounded LRU with
      `putIfAbsent`, and a concurrency test proves twelve simultaneous
      redeliveries process exactly once.
- [x] Circuit breaker and retry with exponential backoff (Resilience4j),
      including the retry-storm argument and which exceptions are worth retrying.
- [x] Correlation-ID propagation across the hop — filter → MDC → `TaskDecorator`
      → RestClient interceptor → header → filter in the other JVM.
- [x] `@TransactionalEventListener(AFTER_COMMIT)`, with the two failures the
      phase prevents: notifying about an enrollment that then rolled back, and
      holding the course row lock across the network call.

**Verified by running both processes.** `TRACEME02` appears in both logs. With
the notification service killed, enrollments still return 201 and the client logs
the fallback. With the breaker configured, `resilience4j_circuitbreaker_state`
went to **open** after five failures, and `retry_calls_total` recorded
`failed_with_retry`. Three identical deliveries produced one email and two
deduplications. 7 automated tests in `notification-service`.

**Two real bugs found by doing it, both silent:**

1. **`@Async` fell back to a default executor.** `@EnableScheduling` contributes
   `taskScheduler` and `AsyncConfig` contributes `notificationExecutor`; neither
   is named `taskExecutor`, so Spring logged a WARNING, picked a default without
   the `TaskDecorator`, and **the correlation id was silently lost across the
   thread boundary** — the receiving service logged its own id instead. Fixed by
   naming the executor explicitly.
2. **A fallback on the inner annotation disabled the retry entirely.** With
   `fallbackMethod` on both `@Retry` and `@CircuitBreaker`, the breaker caught the
   exception, ran the fallback and returned normally — so `@Retry` saw a success
   and never retried. The metric said so:
   `retry_calls_total{kind="successful_without_retry"} 3.0` while the downstream
   service was refusing every connection. The fallback belongs on the OUTERMOST
   annotation only.

- [x] **Chapter 45 written** — `Distributed`, including both silent bugs as
      checkpoint questions.

**Still open on B3:**

- [ ] Kafka or RabbitMQ replacing the HTTP call. Genuinely optional — the
      at-least-once and idempotency lessons are already made, and a broker needs
      Docker.
- [ ] The transactional outbox. `EnrollmentEventPublisher` documents the dual
      write problem and points at the Jakarta EE mail outbox, which already
      implements it — so the repository has the pattern, just not on this path.

### B4 — Angular, at parity with React · **DONE, 2026-09-05**

Built as `angular-client/`. See [angular-client/README.md](../angular-client/README.md).

- [x] A small Angular app consuming this API: `@Component`, `@Injectable`
      service, `HttpClient`, an HTTP interceptor.
- [x] RxJS to the depth a backend developer needs: `Observable` vs `Promise`,
      `pipe`, `map`, `catchError`, `throwError`, and a written-out note on when
      unsubscribing actually matters.
- [x] TypeScript beyond one interface: generics (`PageResponse<T>`), `strict`
      plus `strictTemplates`, `Omit<>`, and discriminated unions for both
      `EnrollmentStatus` and the three-state `RemoteData<T>`.
- [x] Standalone components and signals — no `NgModule` anywhere, `@if`/`@for`/
      `@switch` control flow, `signal()` and `computed()`.
- [x] Forms and client-side validation mirroring Bean Validation, with the
      subset-not-superset rule written down.
- [x] **Beyond the original scope**, because they turned out to be the
      interesting parts: branching on `errorCode` rather than message text, the
      `status === 0` case, the empty state, and a CORS configuration including
      the `exposedHeaders` list people forget.

**Verified end to end in a browser** against the running Spring service: the
list renders, a 409 renders the mapped human message, and the correlation id
reaches the user.

- [x] **Chapter 46 written** — `Angular`.

**Still open on B4:**

- [ ] No component tests. `ng test` needs Karma/Jasmine wiring that is not set
      up, so the `RemoteData` narrowing and the `errorCode` switch are proven
      only by hand in a browser. This is the honest gap in that module.
- [ ] No routing (two panels, one page) and no auth (there is none on the
      server either).

### B5 — Data & Analytics · **DONE, 2026-09-05**

Built in `spring-service/.../reporting/`, plus migration
`V6__course_statistics.sql` in the root project.

- [x] Window functions, executed and asserted: `RANK` vs `DENSE_RANK` over a
      `PARTITION BY`, `LAG` over a CTE, and `SUM(COUNT(*)) OVER ()` for
      percentage-of-total. Ten tests in `ReportingTest` prove the numbers.
- [x] OLTP vs OLAP made concrete rather than asserted: one package serves
      **live** queries (accurate, they scan) and **materialised** ones (stale by
      up to ten minutes, effectively free), and every materialised response
      carries `computedAt`.
- [x] Real reporting endpoints: `/funnel`, `/department-ranking`,
      `/year-over-year`, `/course-statistics`, `/under-subscribed`.
- [x] A chunked scheduled job, with a written argument for **why not Spring
      Batch** for one set-based statement — which is the more useful answer.
- [x] `GROUPING SETS` deliberately **skipped**: not portable to H2, and this
      codebase trades PostgreSQL-only SQL for SQL the tests can execute. Same
      reason `FILTER (WHERE ...)` is written as `SUM(CASE WHEN ...)`.

- [x] **Chapter 43 written** — `Analytics`.
- [x] The vocabulary page (ETL/ELT, warehouse, star schema, dbt, Airflow, Spark)
      — written into [docs/ADJACENT-TECH.md](ADJACENT-TECH.md).

**Still open on B5:**
- [ ] None of the native SQL has run against real PostgreSQL yet (Docker), so it
      is proven on H2 only.

### B6 — Cloud · **WRITTEN, not deployed, 2026-09-05**

Written as [docs/CLOUD.md](CLOUD.md), which states at the top that nothing has
been deployed.

- [x] Managed Postgres, and what actually changes: the URL, and the pool-size
      arithmetic (per instance) against the instance connection limit (per
      database) that nobody does until connections start being refused.
- [x] Object storage, and the presigned-URL rule — never stream a large file
      through a request thread.
- [x] IAM in one page: the application gets no password at all, and why that is
      a better interview answer than "we use environment variables".
- [x] Terraform at reading depth, including the state-divergence trap.
- [x] **A table of the eight things that would have to change in this codebase.**
      Four are already done (relaxed binding for secrets, Flyway as a separate
      step, JSON logging, the liveness/readiness split) — which is the point of
      having built the platform features first.

**Still open on B6:**

- [ ] Actually deploy something. Until then the document is a design, not
      experience, and it says so.
- [ ] Write the `prod` profile — section 3 of that document is its specification.
- [ ] `server.shutdown: graceful`.

### B7 — Smaller gaps · **6 of 7 DONE, 2026-09-05**

- [x] **OpenAPI / Swagger** — springdoc, live at `/swagger-ui.html`, with the
      code-first vs design-first argument written down.
- [x] **API versioning** — `/api/v1` and `/api/v2` served at once, with a real
      breaking change (nested professor) to justify v2, the additive-vs-breaking
      distinction, the three carrying strategies, and RFC 8594
      `Deprecation`/`Sunset` headers.
- [x] **Caching** — `@Cacheable` with Caffeine, `recordStats()` so the hit ratio
      is measurable, eviction written in the same class as the cache, and the
      three questions to answer before adding one.
- [x] **MapStruct** — `EnrollmentMapper` is generated, `CourseMapper` stays
      hand-written, so the trade is visible side by side.
      `unmappedTargetPolicy=ERROR` from the first commit. Lombok is discussed
      and deliberately not used (records cover most of it, and `@Data` on a JPA
      entity is the equals/hashCode trap at its worst).
- [x] **Observability** — Micrometer to Prometheus at `/actuator/prometheus`, a
      `logback-spring.xml` with a `json` profile for structured logging, and a
      timer plus outcome tag on the batch job.
- [x] **Modern concurrency** — virtual threads enabled, with the honest caveat
      that they move the bottleneck to the connection pool rather than removing
      it, and the JDK 21 pinning note.
- [x] **SOAP / JAX-WS** — written into [docs/ADJACENT-TECH.md](ADJACENT-TECH.md):
      WSDL as design-first twenty years early, WS-Security as the reason it
      persists in banking, and the honest answer to give.
- [x] **Chapter 47 written** — `The platform`, covering all six of the above
      plus an index of the five reference documents.

**Found and fixed while doing this**, and worth recording because it was a real
bug: the catch-all `@ExceptionHandler(Exception.class)` was swallowing Spring
`NoResourceFoundException`, so **every unknown path returned 500 instead of 404**
and logged a stack trace. The same applied to the wrong HTTP verb and to
malformed JSON. Four handlers added, three regression tests. It was caught by a
test asserting that `/actuator/env` is closed — a security assertion that found
a correctness bug.

---

## C. Two hazards when acting on this list

**Renumbering.** Chapters are cross-referenced in prose ("chapter 11 spent a
whole chapter on…", "see chapter 15"), in the requirement→chapter table in
ch. 36, and in flashcard answers. Inserting a chapter mid-course silently
falsifies all of them. Either append new chapters at the end, or renumber every
prose cross-reference in the same commit.

**Scope of the pivot.** Adding B1–B6 roughly doubles the course and turns one
repository into three (Jakarta EE app, Spring service, Angular client). Decide
whether the fieldbook stays *one application explained forty ways* — its current
strength — or becomes a multi-repo curriculum, before writing chapter 42.

---

## D. The second advert — a graduate academy

Different genre from advert 1. This is an academy/graduate-programme listing:
the technology list is what the *programme* promises to expose you to over a
year or two, not what is checked on day one. Treat the two tiers differently.

### D1 — Overlaps advert 1, already in the backlog above

| Line from the advert | Verdict | Backlog item |
|---|---|---|
| Microservizi | **partial — concepts only** | B1, B3 |
| Angular | **partial — a translation table, no app** | B4 |
| API | **covered, strong** | — ch. 12, 13, 25 |
| Container | **covered** | — ch. 33 + the repo's own `docker-compose.yml` |
| Cloud | **partial — one paragraph** | B6 |
| "linguaggi più richiesti" | **covered for Java** | B4 covers the TypeScript half |

Nothing new to add for these. B1–B6 already answers them.

### D2 — Genuinely new · **DONE, 2026-09-05**

**The full software lifecycle, "dal design all'implementazione".**
The course is strong from the second half of that sentence onward: layering
(ch. 02), domain modelling (ch. 14), SOLID and patterns (ch. 31), review and
iterations (ch. 22). What is absent is the *design* half as an activity with
artifacts. `UML`, `sequence diagram`, `C4`, `SDLC` and `architecture decision`
all return zero. `lifecycle` appears 22 times and every one is a JPA entity or
a CDI bean.

Written as [docs/DESIGN-LIFECYCLE.md](DESIGN-LIFECYCLE.md).

- [x] Requirement → analysis → design → implementation, walked on the **waitlist**
      — with the five business questions the one-sentence requirement does not
      answer, and the observation that "first in the queue" versus "whoever is
      fastest" is a week of work versus a day.
- [x] All four diagrams, drawn for this project: class, sequence, C4 container,
      ER. The class diagram earns its place by forcing a real decision — a
      separate `WaitlistEntry` versus a new `EnrollmentStatus` — which
      `occupiesSeat()` and the unique constraint settle in ten minutes rather
      than in review after two days.
- [x] ADRs, with a worked example (the notification split) and the one rule:
      never edit an accepted one.
- [x] Where design lives in an Agile team, and the proportionality rule — design
      effort scales with the cost of reversing the decision.

**Certifications** — written as [docs/CERTIFICATIONS.md](CERTIFICATIONS.md).

- [x] OCP Java SE 21 (`1Z0-830`), Spring Certified Professional, AWS Cloud
      Practitioner, CKAD — with cost, time, and honest market value.
- [x] The chapter-to-objective map, which identifies **two real gaps**: JPMS and
      NIO.2 appear nowhere in this repository.
- [x] The framing that matters: take one when somebody else is paying, which is
      exactly why the line is on an academy advert.

### D3 — Buzzword tier · **DONE, 2026-09-05**

Blockchain, AI/ML, Next Generation Mobile Apps and IoT. Current state:

| Term | Mentions in the course |
|---|---|
| `blockchain`, `smart contract` | 0 |
| `machine learning` | 1 — as a **wrong answer** in a quiz |
| `IoT`, `MQTT` | 0 |
| `mobile`, `React Native`, `Flutter` | 0 |
| `Android` | 1 — also a **wrong answer** in a quiz |
| AI | 13 — all in ch. 35, about *using* assistants |

Two things follow from this.

First, **the course already has a position**, and it is a defensible one: the
quiz in ch. 36 offers "Java plus machine learning" and "Java plus Android" as
distractors, explained as *"a different career path with different
prerequisites"* and *"a separate market with its own stack"*. That is correct
and should not be softened. A junior who splits attention across blockchain, ML,
mobile and IoT gets hired for none of them.

Second, the position is currently only implied — buried in two quiz answers.
An academy advert will put these words in front of a candidate in an interview,
and "that's a different career path" is not a complete answer when the company
is offering to train you in it.

- [ ] One chapter, at the very end, near ch. 36: **the words on the academy
      advert, and what a Java developer actually does next to each.** Not how to
      build them — what the backend job looks like when one is in the product.
  - [ ] AI/ML: you serve the model, you do not train it. Feature pipelines,
        batch scoring, an inference endpoint behind your REST API. This connects
        straight to B5, and it is what advert 1's "Data & Analytics" meant.
  - [ ] Blockchain: a distributed append-only ledger; the honest junior answer
        is that Java work near it is ordinary integration work.
  - [ ] Mobile: the backend's job is the API contract and versioning — which is
        B7's API-versioning item, arriving from a second direction.
  - [ ] IoT: many small devices, unreliable networks, MQTT instead of HTTP,
        time-series data. The link back to at-least-once delivery and
        idempotency in B3 is the useful one.
- [ ] Keep ch. 35 as it is. "Familiarity with AI development tools" on an advert
      means the assistant, not the model, and ch. 35 already answers that well.

### D4 — How to read an academy advert · **DONE, 2026-09-05**

Written as [docs/READING-ADVERTS.md](READING-ADVERTS.md).

- [x] The two genres — filter versus brochure — and how to tell them apart from
      the verbs.
- [x] What actually gets screened, in order, and the observation that blockchain
      and IoT are not on that list at all.
- [x] The one-with-a-reason answer, worked through using the reporting layer.
- [x] Academy as a first job: four arguments each way, and the summary that it is
      a very good first job and a mediocre third one.
- [x] Four questions for reading any advert.
