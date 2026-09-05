# From design to implementation

**Backlog item D2, first half.** The academy advert promises the *"intero ciclo
di vita del software, dal design all'implementazione"*. The course is strong from
*implementazione* onward — layering (ch. 02), domain modelling (ch. 14), SOLID
(ch. 31), review and iterations (ch. 22). What was missing is **design as an
activity with artifacts**: `UML`, `sequence diagram`, `C4` and `SDLC` return zero
matches in the whole fieldbook, and all 22 occurrences of "lifecycle" are a JPA
entity or a CDI bean.

---

## 1. The lifecycle, and where the arguing happens

```
requirement → analysis → design → implementation → test → deploy → operate
```

Waterfall runs that once, left to right, with a signed document at each boundary.
Agile runs it **per story, per iteration**. The phases did not disappear — chapter
22 is explicit that iterations are a scheduling change, not a licence to skip
thinking. What changed is the *size of the batch* and the *cost of being wrong*.

**"No big design up front" is not "no design."** It means: design the thing you
are about to build, not the thing you might build in a year. The distinction
matters because juniors are frequently told the first sentence and hear the
second.

The practical rule most teams settle on: **design is proportional to the cost of
reversing it.** Renaming a field is reversible in an afternoon; choosing to split
enrollments from courses across two services (chapter 33's "bad cut") is not
reversible at all. Spend design effort where undo is expensive.

---

## 2. A feature, walked from requirement to code

The waitlist — a feature this project does *not* have, chosen deliberately so the
walk is real rather than reverse-engineered from an answer.

### The requirement, as it arrives

> *"Students should be able to join a waiting list when a course is full."*

One sentence, and it is not buildable. **Analysis is the activity of turning it
into decisions**, and the output is questions, not diagrams:

- When a seat opens, who gets it — first in the queue, or is it offered to
  everyone and taken by whoever is fastest?
- Is a seat *reserved* for the offered student, and for how long?
- Can a student be waitlisted for a course they previously withdrew from?
- Does joining the waitlist count against any per-student limit?
- What happens to the waitlist when enrollment closes?

**Every one of these is a business decision, and none of them is a technical
one.** Answering them yourself is the single most common junior mistake, because
your guess becomes a rule nobody agreed to and nobody can find later.

The best question in that list is the first, and it is worth seeing why: "first in
the queue" needs an ordering, a notification, and a reservation timeout —
perhaps a week of work. "Whoever is fastest" needs a list and nothing else —
perhaps a day. Same sentence in the requirement; two very different builds.

### The acceptance criteria

Given/When/Then, from chapter 22, is where the answers get written down:

```
Given a course with no free seats
  And the enrollment window is open
 When a student requests enrollment
 Then they are added to the waitlist at the end
  And the response is 202 with their position

Given a waitlisted student at position 1
 When another student withdraws
 Then the waitlisted student is enrolled automatically
  And the seat is not offered to anyone else
```

That second criterion is the whole design decision from the previous section,
now testable. If it cannot be written this way, it has not been decided.

### The design, as four artifacts

The four a junior is actually asked to read or draw. **Reading them matters more
than drawing them** — you will be handed diagrams far more often than asked to
produce one.

#### a. The domain model — a class diagram

```
┌──────────────┐        ┌─────────────────┐        ┌──────────────┐
│   Student    │───────<│   Enrollment    │>───────│    Course    │
└──────────────┘  1   * │  status         │ *    1 └──────────────┘
                        │  grade          │               │ 1
                        └─────────────────┘               │
                                                          │ *
                                                 ┌────────────────┐
                                                 │ WaitlistEntry  │  ← new
                                                 │  position      │
                                                 │  createdAt     │
                                                 └────────────────┘
```

**The design question this diagram forces**, and the reason it is worth ten
minutes: is `WaitlistEntry` a new entity, or is it `Enrollment` with a new
`WAITLISTED` status?

The second is tempting — no new table, no new repository. It is wrong, and the
existing code says why: `EnrollmentStatus.occupiesSeat()` decides the seat count,
and a waitlisted student must not occupy a seat. Adding a status that returns
`false` there means every existing query filtering on status has to be rechecked,
and the unique constraint on `(student_id, course_id)` would stop a waitlisted
student from ever becoming enrolled without an update.

A separate entity leaves all of that alone. **Ten minutes with a diagram, versus
finding it in code review after two days of work** — that is the entire argument
for design, and it is more persuasive than any methodology.

#### b. One request — a sequence diagram

```
Student    Controller    Service      CourseRepo   WaitlistRepo    DB
   │            │           │             │             │           │
   │──POST─────>│           │             │             │           │
   │            │──enroll──>│             │             │           │
   │            │           │──findForUpdate()────────────────────>│ SELECT..FOR UPDATE
   │            │           │<────────────────────────────────────│
   │            │           │──countOccupiedSeats()───────────────>│
   │            │           │<─── 30 of 30 ───────────────────────│
   │            │           │                                      │
   │            │           │  full → waitlist instead             │
   │            │           │──save(WaitlistEntry)─────>│           │
   │            │           │                           │──INSERT─>│
   │            │<──position 3──                        │           │
   │<─202───────│           │                           │           │
```

**What a sequence diagram is for**: it shows *order* and *lifetime*, which a class
diagram cannot. The lock is taken before the count and held to the end — the
design decision `EnrollmentService.enroll` already documents in prose, drawn.

It is also where you notice that the lock is held across the waitlist insert too,
which is correct here (both touch the same course) and would be a problem if the
waitlist insert were slow.

#### c. The system — a C4 container diagram

**C4** is four levels of zoom: **Context** (systems and people), **Container**
(deployable things), **Component** (inside one container), **Code** (classes). In
practice teams draw levels 1 and 2 and skip 3 and 4, because the code is the
level-4 diagram and it never goes stale.

Level 2 for this repository, as it now stands:

```
┌─────────────┐   HTTPS    ┌──────────────────┐  JDBC   ┌──────────────┐
│   Browser   │───────────>│ enrollment-spring│────────>│  PostgreSQL  │
│  (Angular)  │            │   :8281          │         │   :55433     │
└─────────────┘            └──────────────────┘         └──────────────┘
                              │            │
                       HTTP   │            │  (mongo profile)
                              v            v
                   ┌──────────────────┐  ┌──────────────┐
                   │notification-svc  │  │   MongoDB    │
                   │   :8282          │  │   :27117     │
                   └──────────────────┘  └──────────────┘
```

**The value is the arrows, not the boxes.** Every arrow is a failure mode:
the HTTP one to notifications is why `NotificationClient` has a timeout, a retry,
a circuit breaker and a fallback. The MongoDB one is dashed in spirit — the read
model is optional, which is why the auto-configuration is excluded by default.

Drawing this makes "which failures may propagate" a visible question rather than
one nobody asks.

#### d. The tables — an ER diagram

Already in `docs/ARCHITECTURE.md` for the existing schema. The waitlist adds one
table, one foreign key pair, and one unique constraint on
`(student_id, course_id)` — the same constraint as enrollments, for the same
reason.

**Notice that the ER diagram and the class diagram are not the same picture**, and
that the difference is exactly what JPA exists to bridge. A many-to-many is one
box and a line in the class diagram, and a join table in the ER diagram.

### Then implementation

Migration → entity → repository → service → controller → tests. That path is
what the rest of the repository already demonstrates.

---

## 3. Architecture Decision Records

`docs/ARCHITECTURE.md` already documents decisions in prose. **ADRs** are the same
content in a format that has one useful property: they are *immutable and
appended*, so the reasoning survives the decision.

The format is deliberately tiny:

```markdown
# ADR-004: Extract notifications into a separate service

Date: 2026-09-05
Status: accepted        (proposed | accepted | deprecated | superseded by ADR-N)

## Context
Notifications already listen to events, never participate in the enrollment
transaction, and nobody waits for them. Chapter 33 identifies this as a good
service boundary. We want to demonstrate the cost of one.

## Decision
Extract into notification-service, called over HTTP after the enrollment
transaction commits.

## Consequences
+ The seam is real and the cost is measurable.
+ Notifications can fail without affecting enrollments.
- Nine new concerns: client, two timeouts, retry, backoff, circuit breaker,
  fallback, idempotency key, header propagation.
- An event can be lost if the process dies between commit and delivery.
  Accepted; the transactional outbox is the fix if it matters.
```

**Why it earns its place**: eighteen months later, somebody asks "why is this a
separate service?" The code cannot answer — it shows *what*, never *why not
otherwise*. Git history is closer but unsearchable in practice. An ADR answers in
thirty seconds, and — the part that matters most — it **records the options that
were rejected**, which is the information that is otherwise lost completely.

**The one rule**: never edit an accepted ADR. Write a new one that supersedes it.
The point is the trail, and an edited record is not a trail.

---

## Still open

- [ ] Convert the existing decisions in `docs/ARCHITECTURE.md` into numbered ADRs
      — the notification split, PostgreSQL over MongoDB for enrollments, no
      aggregator POM, MapStruct for one mapper and not the other.
- [ ] Actually build the waitlist. The analysis above is the specification, and
      it would be the best exercise in the repository — it forces the design
      question about the seat rule that `occupiesSeat()` makes visible.
- [ ] Mermaid versions of the four diagrams. ASCII is honest and diffable; the
      fieldbook renders HTML and could show them properly.
