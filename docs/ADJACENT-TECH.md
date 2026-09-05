# The words on the advert, and what a Java developer does next to each

**Backlog items D3, B5's vocabulary page, and B7's SOAP page.**

Blockchain, AI/ML, mobile and IoT appear on the academy advert. In the fieldbook,
`blockchain`, `IoT`, `mobile` and `React Native` return **zero** matches;
`machine learning` and `Android` return **one each — both as wrong answers in a
chapter 36 quiz**, explained as *"a different career path with different
prerequisites"* and *"a separate market with its own stack"*.

**That position is correct and this document does not soften it.** A junior who
splits attention across blockchain, ML, mobile and IoT gets hired for none of
them.

But it is currently only *implied*, buried in two quiz distractors — and "that's a
different career path" is not a complete answer when a company is offering to
train you in it. So: one page, no code, on what the **backend job** looks like
when each of these is in the product.

---

## AI and machine learning

**You serve the model. You do not train it.**

That single sentence is the whole of it for a backend developer, and it is what
separates a credible answer from an aspirational one. The people training models
have a statistics background and a different job title. The Java role next to them
is real, common, and looks like this:

- **A feature pipeline.** Aggregate raw events into the inputs a model needs.
  That is `ReportingRepository` — window functions, group-bys, a scheduled job
  writing a derived table. Genuinely the same work.
- **Batch scoring.** Run the model over a set of rows on a schedule, write the
  scores back. `StatisticsRefreshJob` with a different computation in the middle.
- **An inference endpoint.** The model runs behind an HTTP API — usually Python,
  usually somebody else's. Your service calls it. **Which means it is
  `NotificationClient` again**: a timeout, a retry, a circuit breaker, and a
  decision about what to do when it is unavailable.

That last point is the most useful thing in this section. **Calling a model is
calling a slow, occasionally-unavailable remote service** — a category this
repository now handles properly. Inference is slower than a database query and
more variable, so the timeout and the fallback matter *more*, not less. "What does
your application do when the model service is down" is a real interview question,
and "degrade to the previous score, or to a rule" is a real answer.

This is also exactly what advert 1 meant by **"Data & Analytics"** — see
`B5` in the backlog and the `reporting` package, which is that work.

**Chapter 35 stays as it is.** *"Familiarity with AI development tools"* on an
advert means the assistant, not the model, and chapter 35 already answers that
question well. Two different things share one acronym.

---

## Blockchain

**A distributed, append-only ledger that a set of mutually distrusting parties
can agree on without a central authority.**

The honest junior answer: **Java work near blockchain is ordinary integration
work.** You call a node over JSON-RPC, you wait for confirmations, you handle
reorganisations. The interesting parts — consensus, cryptography, the contracts
themselves — are somebody else's, usually in Solidity.

Three things worth knowing, because they connect to what you already understand:

- **Finality is probabilistic, not immediate.** A transaction is "probably"
  confirmed after N blocks. There is no COMMIT. Every mental model built on
  chapter 11's transactions has to be set aside.
- **Writes cost money and are slow.** Seconds to minutes, with a fee. It is the
  most expensive database anyone has ever built, which is why the design question
  is always *"why does this need to be trustless?"*
- **You cannot delete anything.** Which collides directly with GDPR, and is a live
  legal argument rather than a solved problem.

**The right question, and it is fair to ask it in an interview**: what does the
ledger give you that a database with an audit table and a signature does not? For
most business problems the answer is "nothing, and it costs a great deal more".
Being able to ask that pleasantly is worth more than knowing what a Merkle tree is.

---

## Next Generation Mobile Apps

**The backend's job is the API contract and its versioning.** That is not a
diminished role — mobile makes it considerably harder, and for one specific
reason.

**You cannot force an upgrade.** A web client is whatever you deployed this
morning. A mobile client is whatever the user last chose to update, which may be
eighteen months old, and app store review adds days to every fix. So:

- **Breaking changes are genuinely breaking**, in a way they are not for a web
  client you can redeploy. This is what `/api/v1` and `/api/v2` in
  `spring-service` exist to demonstrate, and mobile is the case where that
  discipline stops being theoretical.
- **Additive change is your main tool.** A tolerant client ignores fields it does
  not know, so adding is safe and removing is not. `CourseV2Response` documents
  which is which.
- **The `Sunset` header matters**, because you need to know whether anyone is
  still on v1 before you can delete it — and with mobile, somebody always is.

Beyond that: payloads should be small (mobile networks are worse than you think),
requests should be batchable (latency dominates), and everything must tolerate
being retried, because a phone loses signal mid-request constantly. **Which is
idempotency**, and `NotificationService` is the worked example.

**React Native and Flutter** are the cross-platform frameworks; **Kotlin** for
native Android, **Swift** for iOS. Kotlin is worth a sentence for a Java
developer: it runs on the JVM, interoperates with Java completely, and is a
genuinely small step — but Android development is still a separate discipline, as
chapter 36's quiz says.

---

## IoT

**Many small devices, unreliable networks, time-series data.**

The Java role is the ingestion side, and every property of it is one you have
already met somewhere in this repository:

- **MQTT instead of HTTP.** A publish/subscribe protocol designed for tiny
  messages over bad links. The mental model is a message queue, which chapter 33
  already frames as "a CDI event once the listener lives in another process".
- **Delivery is at-least-once**, which means **consumers must be idempotent** —
  literally the same requirement, for literally the same reason, as
  `NotificationService`. A device with a flaky connection republishes, and your
  handler must not double-count.
- **The volume is in writes, not reads**, and the data is time-series: append-only,
  queried by range, rarely updated. That is a different shape from anything in
  this application, and it is why TimescaleDB and InfluxDB exist.
- **Devices lie about time.** Clock drift on cheap hardware is real, so the event
  timestamp and the arrival timestamp differ — sometimes by days. `occurredAt` on
  `EnrollmentCreatedEvent` carries a comment about exactly this distinction.

Three of those four are patterns already in this codebase, arriving from a
different direction. That is the useful observation to carry into an interview.

---

## The Data & Analytics vocabulary

**Backlog item B5's remaining piece.** Enough not to be lost in the first sprint
planning. One honest sentence each.

| Term | What it is | Where you would meet it |
|---|---|---|
| **ETL** | Extract, Transform, Load — pull data out, reshape it, write it to a warehouse | The classic order, when compute was expensive |
| **ELT** | Load it raw first, transform *inside* the warehouse | The modern order, because warehouse compute is now cheap |
| **Data warehouse** | A database optimised for analytical queries over history | Snowflake, BigQuery, Redshift |
| **Data lake** | Raw files in object storage, schema applied on read | S3 or GCS plus a query engine |
| **Star schema** | One fact table (enrollments) surrounded by dimension tables (student, course, date) | The standard warehouse shape; deliberately denormalised |
| **Fact / dimension** | Facts are measurable events; dimensions are what you slice them by | `enrollments` is a fact; `courses` is a dimension |
| **dbt** | Transformations as version-controlled, tested SQL | The T in ELT, in practice |
| **Airflow** | A scheduler for pipelines with dependencies between steps | `StatisticsRefreshJob`, grown up |
| **Spark** | Distributed processing for data too large for one machine | Written in Scala/Java — the closest of these to a Java job |
| **Kafka** | A durable, replayable event log | Chapter 33 covers it; the "replayable" part is what makes it more than a queue |
| **OLTP / OLAP** | Transaction processing versus analytical processing | The `reporting` package demonstrates both |
| **Columnar storage** | Store by column, not by row | Why a warehouse scans one column of a billion rows quickly |
| **Medallion** | Bronze (raw) → silver (cleaned) → gold (aggregated) | A naming convention you will hear stated as if it were an architecture |

**The two that actually connect to this repository**: `course_statistics` is a
gold-layer aggregate built by an ELT-shaped job, and the OLTP/OLAP split in the
`reporting` package is the whole distinction in miniature. Saying that is more
convincing than reciting the table.

---

## SOAP and JAX-WS

**Backlog item B7's last entry.** Chapter 36 already names this as the one
genuinely missing piece in a list of ten technologies, and recommends volunteering
it as a gap. Here is enough to say something useful.

**SOAP is an XML-based RPC protocol.** Where REST says *"here is a resource, act
on it with HTTP verbs"*, SOAP says *"here is an envelope containing a method call"*
— and every call is a POST to one endpoint.

What is worth knowing:

- **WSDL** is the contract: a machine-readable XML description of every operation
  and type. `wsimport` (or the `cxf-codegen` Maven plugin) generates Java client
  classes from it. **This is design-first, twenty years earlier** — the same idea
  as an OpenAPI spec generating a client, which is the comparison to draw.
- **JAX-WS** is the Jakarta API for it, exactly parallel to JAX-RS.
  `@WebService` on a class, `@WebMethod` on a method.
- **It is strongly typed and verbose.** The XML is large, the tooling is heavy,
  and the type safety is real. That trade was worth more in 2005 than now.
- **WS-Security, WS-AtomicTransaction** and the rest of the "WS-\*" family are the
  reason it survives: signed and encrypted messages at the *message* level rather
  than the transport level, and distributed transactions. Banking and public
  sector integrations still genuinely need those.

**Where you will meet it in Italy**: banks, insurance, public administration, and
any integration with a system older than about 2012. The listings that ask for
JAX-WS are not confused — they have a real system that speaks it.

**The honest answer**: *"I have not used SOAP. I understand it is XML-RPC with a
WSDL contract that generates client code, and that WS-Security is why it persists
in banking. I would expect to be generating a client from someone else's WSDL
rather than writing a service."* That is accurate, and it is what the job usually
is.

---

## The rule this whole document exists to state

**Learn one of these properly, and only when a job actually requires it.**

Chapter 36's finding is that the pairing on junior Italian adverts is
**Java + a JVM framework + SQL + Git**, and that the fourth is the one people
forget to demonstrate. Nothing on this page displaces that.

What this page buys is the ability to have a sensible thirty-second conversation
about each, and to say *"that is a different career path, and here is what the
backend job next to it looks like"* — which is a much better answer than either
bluffing or going blank.

---

## Still open

- [ ] This is prose in `docs/`, not a chapter in `tutorial.html`. Turning it into
      a chapter is subject to the renumbering hazard in section C of the backlog:
      append at the end, or renumber every cross-reference in the same commit.
- [ ] Chapter 36's quiz distractors should point here once it is a chapter, so the
      position is stated rather than implied.
