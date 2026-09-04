# Accounts, progress and notes

How the fieldbook's own multi-user layer works, and why each piece is the way it
is. This is the worked answer to the exercise fieldbook chapter 15 sets — the
enrollment API next door is still deliberately unauthenticated, because it is
the specimen that chapter dissects.

Everything here lives under `it.unicam.cs.enrollment.fieldbook`.

---

## 1. Why it is a separate bounded context

A learner is not a student, even when they are the same person.

`Student` belongs to the enrollment domain: it has a matriculation number, a
status the registrar controls, and enrollments that carry grades.
`LearnerAccount` belongs to the fieldbook: it has a password, a reading streak
and some sticky notes.

Merging them looks like sensible reuse for about a week. Then the registrar
suspends a student and the fieldbook logs them out; then somebody who is not a
student wants to read the course and you discover that "student number" is
mandatory. The word for this is **bounded context**: the same noun — user,
account, customer — means different things to different parts of a business, and
the mistake is assuming one table can serve both meanings.

What the two contexts share is a **shared kernel**, kept deliberately tiny:
`BaseEntity` and `Email`, and the `AbstractJpaRepository` infrastructure. No
foreign key crosses between the two groups of tables, and no query joins them.
That is what would make this splittable into two deployables later — a context
that shares no keys can be lifted out with a data copy; one that joins across
the boundary cannot be moved without rewriting the queries first.

---

## 2. The tables

All created by `V3__fieldbook_accounts.sql`, all prefixed `fieldbook_`.

| table | holds |
| --- | --- |
| `fieldbook_accounts` | one row per person. Email (lower-cased, unique), display name, PBKDF2 hash, timezone, best streak |
| `fieldbook_account_roles` | `@ElementCollection`, primary key on the pair, so a role cannot be granted twice |
| `fieldbook_study_days` | one row per calendar day with activity — the streak is derived from these, never stored |
| `fieldbook_sessions` | one row per signed-in browser. SHA-256 of the cookie, never the cookie |
| `fieldbook_cards` | Leitner box state per question per learner |
| `fieldbook_chapters` | read / best score / attempts / passed, per chapter per learner |
| `fieldbook_notes` | sticky notes, ordered by a fractional `sort_index` |

There is **no `ON DELETE CASCADE` anywhere**. Deleting an account removes its
rows explicitly, in dependency order, in one transaction, in `AccountService`.
Cascading deletes in the schema are convenient and are how people accidentally
delete a great deal of data: the `DELETE` that looked like it touched one row
touched four tables, and nothing in the statement said so.

### Why the streak is derived and not stored

A `streak` column has to be updated by whoever notices the day changed, which
makes it wrong for anyone who studies at 23:59, wrong again across a timezone
change, and unrecoverable once it has drifted. Storing the raw days and
computing on read is marginally more work per request and cannot go stale — the
event-sourcing argument in miniature.

`best_streak` **is** a stored counter, because it is a high-water mark: it can
only be computed from history that is still present, so trimming the days later
would silently lower it. Facts you might one day discard need their summary
kept.

The days are `DATE`, not `TIMESTAMP`. "Which day was that, for you" is a
question about a local calendar, and the zone is stored per account so that a
learner travelling for a week does not lose a streak to a server living in UTC.
Yesterday counts as unbroken: a streak whose last day is yesterday is alive
until midnight. Getting that wrong — resetting at 00:00 — is the single most
complained-about behaviour in every app that has ever had a streak.

---

## 3. Authentication

### Passwords

`PasswordHasher`, PBKDF2-HMAC-SHA256, 210 000 iterations, 128-bit random salt
per row, constant-time comparison via `MessageDigest.isEqual`.

Stored as `pbkdf2-sha256$210000$<salt>$<hash>` — self-describing, so the cost
can be raised later without invalidating existing rows. `needsRehash()` reports
a row written under weaker parameters, and the row is silently upgraded on the
next **successful** login, which is the only moment the plaintext exists.

Argon2id is better, because it is memory-hard and therefore resists a GPU
attack in a way PBKDF2 does not. It would mean a third-party dependency; PBKDF2
is in the JDK, so every line of the algorithm path is readable here. In a real
system with a real threat model, take the dependency. What you must never do is
the thing this class exists to avoid: a bare `MessageDigest.digest` of a
password.

### Sessions

`TokenMint` produces 32 random bytes from a `SecureRandom`. The raw value goes
to the browser in a cookie; only its SHA-256 is stored. A leaked database
therefore yields hashes, and a hash cannot be presented as a cookie.

Plain SHA-256 rather than PBKDF2 for the token, and the reason is the whole
point of both: a password is low-entropy and human-chosen, so its hash must be
slow to make guessing expensive; a 256-bit random token has no dictionary and no
brute force worth the name. Applying the same tool to both is the mistake, in
either direction.

**Not a JWT**, and that is a decision rather than an omission. A JWT cannot be
revoked without a denylist — at which point you have a session table again, but
a worse one — and it needs a signing key to rotate and protect. An opaque token
looked up in an indexed table gives revocation for free at the cost of one
primary-key-shaped lookup per request, on a database that is already on the
critical path. JWT becomes the right answer when several independently deployed
services must verify a caller without sharing a database. That is a real
situation; it is just not this one.

### The cookie

`HttpOnly`, `SameSite=Strict`, `Secure` when the request arrived over HTTPS,
`Path` scoped to the application's context root rather than `/`.

Cookie versus `localStorage` has no free answer, and fieldbook chapter 15
refuses to give one:

| | `localStorage` | `HttpOnly` cookie |
| --- | --- | --- |
| readable by script | yes — any XSS steals it | no |
| sent automatically | no | yes — which enables CSRF |
| main risk | token exfiltration | cross-site request forgery |

The cookie is chosen because XSS exfiltration is silent and permanent, while
CSRF is bounded to actions rather than credentials and has two good mitigations
— both of which are applied:

1. `SameSite=Strict`, enforced by the browser.
2. `CsrfFilter`: state-changing requests must carry `X-Fieldbook-Request`. A
   cross-site `<form>` cannot set a header at all — that is a hard limit of
   HTML — and a cross-origin `fetch` that adds one triggers a preflight this
   application never approves. The *value* is never checked; its presence is the
   signal.

`GET` is exempt from (2), which is a rule about your own design as much as about
the filter: the moment a `GET` has a side effect, the exemption becomes a hole,
and the bug to fix is the `GET`.

`CsrfProtected` is a separate annotation from `Authenticated` because login and
registration need CSRF protection while having no session to authenticate —
**login CSRF** is real: force a victim's browser to sign in as the attacker, and
everything they then do is recorded in an account the attacker can read.

### Not leaking who has an account

- Login returns one 401 for both "unknown address" and "wrong password", and
  runs the hash even for an unknown address so the two take the same time.
  Returning early would make a miss measurably faster, and that timing
  difference is itself the oracle.
- Registration with an existing address falls through to a login attempt with
  the supplied password, rather than answering "already registered". The real
  product answer is to send an email either way and say nothing in the response;
  that needs a mail server.
- `NoteService` answers **404**, not 403, for somebody else's note. 403 confirms
  the row exists.

### Throttling

`LoginThrottle` keeps two counters, because there are two attacks:

- **per account** (8 failures / 15 min) — a password list against one email;
- **per source address** (30 / 15 min) — credential stuffing, where no single
  account is tried twice and the per-account counter never fires.

Implementing only the first is the common half-measure, and it stops the attack
nobody is running. A success clears the account counter but **not** the source
counter: guessing one password out of a thousand attempts should not buy a clean
slate for the other 999.

It is also the one class in the codebase that is `@ApplicationScoped` **and**
holds mutable state, so it is written the way that requires:
`ConcurrentHashMap`, `AtomicInteger`, and `computeIfAbsent` rather than
check-then-put.

It is in memory, so it resets on redeploy and is per node. On a cluster an
attacker gets the budget once per node. The real answer is a shared store and a
limiter at the ingress; this is the cheap version, and cheap beats absent.

---

## 4. Synchronisation

The page works with **no account at all** — everything is in `localStorage`.
Signing in does not switch that off; it adds a server copy. So there are two
copies of the same data being edited independently, which is distributed systems
in miniature and has no free answer.

### The policy

**Last write wins, per item**, on the item's own merge clock. Chosen for one
property: it is easy to explain and therefore easy to predict.

Its cost is equally plain — study on your phone, then on a laptop that has been
offline since yesterday, and the laptop's older answer *for that one card* is
discarded. Not the whole record; one card.

Three things make it tolerable:

- **Progress is near-monotonic.** Boxes go up far more often than down.
- **Ties resolve towards keeping progress.** Where the two sides cannot be
  ordered — an identical timestamp, or a client with no clock — the higher box
  wins. Treating missing data as newest is how a buggy old client quietly wipes
  a good record.
- **Chapters do not use a clock at all.** Every field there is monotonic in one
  direction — you cannot un-read a chapter, and a best score is a maximum by
  definition — so "take the better of each" is a conflict-free merge.

The genuinely correct answers are vector clocks or a CRDT. Knowing which one you
picked, and what it costs, is the part that matters.

### What a sync never does

**It never deletes.** A card the client does not mention is left alone rather
than removed, because "not mentioned" and "deleted" are indistinguishable over a
lossy connection and guessing wrong destroys data. Deletion is its own explicit
endpoint.

### The merge clock

`CardProgress.syncedAt` is a separate column from `BaseEntity.updatedAt`, and
that is not redundancy:

- `updatedAt` is an **audit** column, maintained by a JPA lifecycle callback,
  and therefore `null` until the row is flushed.
- `syncedAt` is a **merge clock**: when the learner last *answered*, on whatever
  device.

Borrowing the audit column for the merge saves one column and costs a subtle
bug — a row created earlier in the same transaction has no `updatedAt` yet, so
the comparison silently degrades to "the client always wins". When one field is
asked to mean two things, the two meanings eventually disagree, and the
disagreement shows up as data loss rather than a compile error.

### Why the client sends the course structure

`SyncRequest.catalogue` and `withCheckpoint` describe the chapters. The server
stores neither.

That looks backwards until you notice where the course actually lives: the
chapters and their questions are in one HTML file, which is what makes the
fieldbook work with no server. A second copy on the server would drift, and the
day it drifted every learner's percentage would be quietly wrong.

The trade-off is that a hostile client can lie about the catalogue and change
its own percentage. Accepted deliberately: the number is a study aid for the
person looking at it, not a grade anyone else relies on. The moment it becomes a
credential — a certificate, a mark — this design is wrong and the catalogue has
to move server-side.

### Card identity

`cardKey` is a hash of the **question text**, produced by the browser, not a
position in a list. Numbering would mean that inserting a question at position 12
silently reassigns every learner's history from 12 onwards — they would find
themselves "already knowing" a card they have never seen.

The cost is that fixing a typo resets that card for everybody. That is the right
cost to pay: the alternative silently corrupts data instead of visibly losing a
little.

### Serialising a learner's own writes

Every write path opens with a row lock on the learner's own account
(`LearnerAccountRepository.lockRow`). Without it, two syncs from the same person
— two open tabs is enough — race on three separate unique constraints at once:
the card key, the chapter id and the study day.

The lock is per account, so it costs nothing across learners: contention scoped
to one person's own devices is not contention.

It is taken with a **native** `SELECT id ... FOR UPDATE` rather than
`em.find(id, PESSIMISTIC_WRITE)`, and the reason is worth reading in full in
that method. The short version: `LearnerAccount` has an eager `roles`
collection, so the locking find is a join, PostgreSQL will not accept
`FOR UPDATE` on one, and Hibernate silently degrades to **follow-on locking** —
read the row, then lock it in a second statement, with `WHERE id = ? AND
version = ?`. Those two statements are not atomic, and the gap between them is
exactly the window the lock existed to close. It fails as an
`OptimisticLockException` thrown by the code you added to prevent one, announced
only by an `HHH000444` warning.

---

## 5. Mastery

`MasteryCalculator` is a pure function of its arguments — no clock, no database,
no injection — which is why it is trivially testable.

```
reading the chapter        15%   weak evidence, but not none
the end-of-chapter check   45%   answered once, with the chapter fresh
the spaced cards           40%   answered again, days later
```

The split between the last two is the important one. A checkpoint taken
immediately after reading is mostly a test of short-term memory; the card
component can only rise by answering the same material correctly after a delay,
and it drops back the moment you get one wrong. So the number cannot be finished
in an afternoon, and it is not supposed to be.

Chapters with no checkpoint have their weights **redistributed** rather than
being scored out of a denominator they can never reach — otherwise the course
total could never reach 100%, which reads as broken.

The pass mark is **75%**, which with a four-question checkpoint is exactly "at
most one wrong": clearable having misread something once, not clearable by
guessing at 25% a question. 80% was the first choice and was wrong, because on
four questions it demands a perfect score. A pass mark is a decision about the
number of questions, and the two have to be chosen together.

The constant lives in `ChapterProgress.PASS_MARK` and in the browser. Two copies
of one rule is a smell; the mitigation is that both say so and name each other.

### No points, badges or leaderboards

Because the evidence is not flattering. The meta-analyses find that game
elements reliably raise **extrinsic** motivation, barely move competence, and
fade once the novelty does — while shifting attention from the material to the
reward. A leaderboard also punishes exactly the person it should encourage.

The streak is the one concession, because turning up regularly is a real
predictor and a day counter is honest about what it counts.

---

## 6. The endpoints

All under `/api/fieldbook`. Every state-changing call needs
`X-Fieldbook-Request`; everything except register and login needs the session
cookie.

| method | path | notes |
| --- | --- | --- |
| `POST` | `/auth/register` | 201 + cookie. Duplicate address falls through to a login attempt |
| `POST` | `/auth/login` | 200 + cookie, 401, or 429 with `Retry-After` |
| `GET` | `/auth/me` | the signed-in account |
| `POST` | `/auth/logout` | deletes the row *and* expires the cookie |
| `POST` | `/auth/logout-all` | every session for the account |
| `POST` | `/auth/password` | requires the current password; revokes every session |
| `DELETE` | `/auth/me` | deletes the account and all of its data |
| `GET` | `/progress` | snapshot; catalogue as repeated `?chapter=` params |
| `PUT` | `/progress` | merge and return. **Idempotent** — safe to retry blind |
| `POST` | `/progress/checkpoint` | one attempt; returns `{firstPass}` |
| `POST` | `/progress/read` | mark a chapter read |
| `DELETE` | `/progress` | start the course again; keeps the notes |
| `GET/POST` | `/notes` | list (optionally `?chapter=`), create |
| `PATCH` | `/notes/{id}` | partial update — `null` means "leave alone" |
| `POST` | `/notes/{id}/move` | reorder by averaging two sort indices |
| `DELETE` | `/notes/{id}` | 404, never 403, for somebody else's |

`PUT` for the sync because sending the same body twice must leave the same
state — that is what makes a retry safe over a connection that dropped before
the response arrived. The checkpoint is a `POST` for the opposite reason: two
attempts *are* two attempts.

---

## 7. The pages

There are two front ends onto the same four endpoints, and the duplication is
deliberate rather than accidental.

The **tray inside `tutorial.html`** is the convenient one: sign in without
losing your place in the chapter you are reading. It is part of the single-file
course, so it works wherever that file is opened.

The **standalone pages** are the ones you would build for a product.

| file | what it is |
| --- | --- |
| `signin.html` | Sign in |
| `register.html` | Create an account |
| `area-riservata.html` | Everything the account holds, and everything you can do to it |
| `assets/account.css` | The fieldbook's tokens, copied. Light, dark, and a `[data-theme]` override |
| `assets/account.js` | One `fetch` wrapper, a ProblemDetail reader, a redirect validator, and the paint helpers |

No framework. The whole surface is four endpoints and a cookie, and a build step
would be more machinery than the thing it builds.

### The four things worth reading the source for

**Whether you are signed in is asked, never assumed.** Each page opens with
`GET auth/me`, because the cookie is `HttpOnly` and no script can read it. The
three answers are three different pages: 200 signed in, 401 the form, anything
else — a 404, a 5xx, a rejected fetch — means these files are being served by
something that is not the application, and a sign-in button that can only fail
is worse than saying so.

**`?next=` is validated, not trusted.** An unchecked redirect is the phishing
link that survives a careful reader, because it starts on a domain the victim
trusts. `safeNext` rejects a scheme, a backslash and a leading `//`, then
resolves what is left and requires the same origin anyway — either check alone
has a bypass.

**Nothing user-supplied goes near `innerHTML`.** Names, notes and chapter titles
are all written with `textContent`. Same reason the notes are stored as plain
text: escaping by construction has no bypasses, and sanitisers do.

**The reserved area sends the catalogue.** It reads
`fieldbook.catalogue.v1`, which `tutorial.html` writes into `localStorage` when
it loads, and passes it as repeated `?chapter=` parameters. The server keeps no
copy of the course structure — §4 says why — so without that key the page shows
every stored row and says plainly that the course-wide percentage is missing,
rather than showing a zero it cannot justify.

### What the reserved area can do

Read: mastery ring, chapters read and passed, current and best streak, a
twelve-week study calendar, the per-chapter table, and the notes.
Write: change password, sign out, sign out everywhere, reset progress, delete
the account. Deleting asks you to retype your address rather than to click OK,
because a dialog with an OK button is dismissed by reflex.

---

## 8. What is deliberately missing

- **No email verification.** An address is never proved.
- **No password reset.** Not optional for a real product; needs a mail server.
- **No multi-factor.**
- **No audit log** of sign-ins or of failed attempts.
- **The rate limiter is in memory** — per node, reset by a redeploy.
- **Sticky notes are plain text**, never rendered as HTML or Markdown. That
  removes stored XSS by construction: a note reading `<img onerror=...>` is a
  note about an img tag. "Escape everything" beats "sanitise carefully" for
  user-generated content, because sanitisers have bypasses and escaping does
  not.
- **The enrollment API is still completely unauthenticated**, on purpose.

Writing that list down reads as engineering judgement. Discovering it in review
reads as something else.

---

## 9. Three bugs this shipped with

Kept because the error messages are more instructive than the fixes.

**1. `WELD-001435: not proxyable because it has no no-args constructor`.** The
deployment would not start. A normal-scoped CDI bean is injected as a generated
proxy subclass, and a subclass needs a constructor it can call with no
arguments — so constructor injection alone is not enough. This is why every
service in the codebase also has a `protected` no-arg constructor and non-final
fields.

**2. `LazyInitializationException` on the first authenticated request.** The
streak was computed from a lazy collection on an account the authentication
filter had loaded in an *earlier* transaction. The fix was not to make the
collection eager — that loads every study day on every request, forever — but to
fetch exactly the days, with their own query, where they are needed. The
question is never "eager or lazy"; it is "which query does this use case need".

**3. Two browser tabs produced a 409.** `lastSeenAt` was written through the
managed entity on every request, making a `@Version`-ed row a write hotspot: two
overlapping requests collided over a timestamp nobody competes for. Optimistic
locking is for business state where a human must be told about the conflict.
Telemetry belongs in a bulk `UPDATE` that skips the version, and only when it is
actually stale.

And the fix for (3)'s sibling race made things worse before it made them better
— see the follow-on locking note in section 4.
