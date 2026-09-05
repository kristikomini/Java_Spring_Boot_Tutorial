# `angular-client` — the front end, at parity with the React chapter

Chapter 32 builds a real React component against this API and gives Angular a
six-row translation table — while saying in its own prose that Italian
enterprise shops standardised on Angular. This module closes that gap.

It is a **small** application on purpose. The honest junior claim is *"backend,
with enough front end to consume my own API"*, and this is exactly that much:
two panels, one service, one interceptor.

---

## Run the whole stack

Three commands, **no database and no Docker required**:

```bash
mvn spring-boot:run -f spring-service/pom.xml -Dspring-boot.run.profiles=demo
```
```bash
npm start --prefix angular-client
```

Then open <http://localhost:4280>. The `demo` profile runs the Spring service on
an in-memory H2 seeded with five courses, three students and a course that is
deliberately **one seat from full** so the 409 path is reachable.

To point the client at the **Jakarta EE** service instead, change one line in
[`course.service.ts`](src/app/services/course.service.ts):

```ts
private readonly baseUrl = 'http://localhost:8280/enrollment/api';
```

Nothing the user sees should change. That is the real test of whether the two
implementations are one API — worth doing once by hand.

---

## What it demonstrates, and where

| Chapter 32 claim | Where it is real |
|---|---|
| `@Component` class with a template | [`course-list.component.ts`](src/app/components/course-list.component.ts) |
| `@Injectable` service + DI (*"the one part that will feel familiar"*) | [`course.service.ts`](src/app/services/course.service.ts) |
| `HttpClient` returns an **Observable**, not a promise (*"the genuinely new concept"*) | same file — `pipe`, `map`, `catchError`, `throwError` |
| Signals instead of `useState` | `signal()` / `computed()` in both components |
| `ngOnInit` instead of `useEffect` | `course-list.component.ts` |
| TypeScript beyond one interface | [`course.model.ts`](src/app/models/course.model.ts) — generics, unions, `Omit<>`, `strict` |
| Forms + validation mirroring Bean Validation | [`enroll-form.component.ts`](src/app/components/enroll-form.component.ts) |
| Standalone components and signals (**modern** Angular, not NgModule-era) | no `NgModule` anywhere |
| CORS, and why the server log looks healthy | `CorsConfig` in the Spring module |

### The three ideas worth taking away

**Every remote call renders three states.** Chapter 32's golden rule is
*advisory* prose; here it is a **discriminated union** (`RemoteData<T>`) and a
`@switch`, so a component that forgets the error branch does not compile. Plus
the fourth state everybody forgets — a successful response with zero rows, which
must not render as a blank panel that looks broken.

**Branch on `errorCode`, never on the message.** The form has a `@switch` over
`COURSE_FULL`, `DUPLICATE_RESOURCE`, `PREREQUISITES_NOT_MET` and
`ENROLLMENT_WINDOW_CLOSED`. This is the entire payoff for the RFC 7807 work on
the server: a client can behave differently per failure without parsing prose
that may be reworded next week.

**`status === 0` is its own case.** No response at all — server down, DNS,
network, or CORS. There is no `ProblemDetail` because there was no response, so
the client must supply its own message. This is the branch that fires for the
CORS question chapter 32 asks, and the one people forget until it happens.

### Validation runs twice, deliberately

The validators mirror `@NotNull` / `@Positive` on `EnrollRequest.java`. The
client check is a **convenience**; the server check is the **guarantee** —
anyone can open the console and post whatever they like.

The corollary is the part people get wrong: the client rules may be a **subset**
of the server rules but must never be a **superset**. A client that rejects what
the server would accept makes a feature unreachable with no error anywhere.

---

## Verified

```bash
npm run build --prefix angular-client
```

Builds clean under `strict` **and** `strictTemplates` — the Angular equivalent
of `-Xlint:all`, and the setting most tutorials quietly leave off.

Checked in the browser against the running Spring service:

- The list renders, and `computed()` derives *"5 course(s), 4 open, 398 seats free"*
- A full course dims; a closed window shows a `closed` badge
- A 409 renders **"This student is already enrolled in that course."** — proving
  the `errorCode` branch, not the raw server prose
- The **correlation id reaches the user** (`Reference: e118b438`), which is the
  whole point of `CorrelationIdFilter`
- CORS works cross-origin including the JSON **preflight**, and
  `exposedHeaders` lets JavaScript actually *read* `X-Correlation-Id` — a
  separate list from `allowedHeaders`, and the one people forget

## Not done

- **No tests.** `ng test` needs Karma/Jasmine wiring that is not set up. The
  component logic worth testing is the `RemoteData` narrowing and the
  `errorCode` switch, and both are currently proven only by hand in a browser.
  This is the honest gap in this module.
- **No routing.** Two panels on one page; adding a router to prove Angular has
  one would be ceremony. `provideRouter` with `{path, component}` is the whole
  vocabulary.
- **No auth.** There is none on the server either — chapter 15 makes that
  admission already.
