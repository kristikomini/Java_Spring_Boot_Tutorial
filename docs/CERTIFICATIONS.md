# Certifications, and whether they are worth it

**Backlog item D2, second half.** `certification`, `OCP`, `1Z0` and
`AWS Certified` all return zero matches in the fieldbook, and the academy advert
makes *"certificazioni riconosciute a livello globale"* an explicit promise. This
is the missing page.

---

## The short answer first

**For a junior in the Italian market, a repository you can walk someone through
beats every certification on this list.** Chapter 37 already makes that argument
and it does not change here.

Certifications are worth taking when:

- **Somebody else is paying.** Consultancies and academies frequently do, because
  partner status with Oracle, AWS or Microsoft depends on headcount holding
  certificates. That is the real reason it is on the advert.
- **A job posting lists one as required.** Rare for juniors, common in public
  sector tenders and some banks.
- **You want a syllabus.** This is the underrated one. The OCP objectives are a
  genuinely good, complete list of what "knowing Java" means, and working through
  them finds the gaps you did not know you had.

They are **not** worth paying for yourself, as a junior, to get a first job. The
money is better spent on the time to finish a project.

---

## The four worth knowing about

### Oracle Certified Professional: Java SE 21 Developer — `1Z0-830`

The one people mean by "Java certified".

| | |
|---|---|
| **Cost** | ~€250 for the exam |
| **Time** | 6–10 weeks of evening study from where this course leaves you |
| **Format** | ~50 questions, 120 minutes, ~68% to pass |
| **Value in Italy** | Moderate. Recognised, occasionally listed, never decisive for a junior. |

**What it actually tests**, and the reason it is harder than it looks: it is
extremely precise about the *language*, and barely touches what you do all day.
Expect questions on the exact order of static and instance initialiser blocks,
whether a piece of generic code compiles, sealed classes and pattern matching,
`Stream` laziness, and the `var` inference rules. Almost nothing about Spring,
JPA, HTTP or SQL.

Chapters 04, 05, 06 and 30 cover a real share of the syllabus, and the two areas
this course does *not* prepare you for are **modules (JPMS)** and **NIO.2 file
handling** — neither of which appears anywhere in this repository, which is
itself a fair comment on how often they come up.

**The predecessor** is `1Z0-829` (Java SE 17). Both are current; take the one
matching the Java version you actually use, which for this project is 21.

### Spring Certified Professional — VMware / Broadcom

| | |
|---|---|
| **Cost** | ~€200 exam, and the official course is much more |
| **Time** | 4–6 weeks if you have written Spring; unrealistic if you have not |
| **Value in Italy** | Low-to-moderate as a certificate; the syllabus is excellent |

**More useful as a checklist than as a certificate.** The objectives — bean
lifecycle, proxies and AOP, transaction propagation, Boot auto-configuration,
Spring Data, testing slices, Actuator, Security — are precisely the list of
things a Spring developer is expected to know, and `spring-service/` now
demonstrates most of them.

**The gap between this repository and that syllabus is Spring Security**, which is
the largest single hole on the Spring side. Chapter 15 makes the same admission
about the Jakarta EE application.

### AWS Certified Cloud Practitioner — `CLF-C02`

| | |
|---|---|
| **Cost** | ~$100 |
| **Time** | 2–3 weeks |
| **Value** | The best ratio on this list, and the least technical |

Deliberately broad and shallow: what the services are, what they cost, the shared
responsibility model, the well-architected framework. **It is not a developer
exam** and does not pretend to be.

Worth it because it makes you fluent in the vocabulary in a fortnight, which is
enough to follow a conversation and to read `docs/CLOUD.md` with recognition
rather than faith. The next step up is **Developer Associate** (`DVA-C02`), which
is a real technical exam and a real time commitment.

**GCP's equivalent** is Associate Cloud Engineer; **Azure's** is AZ-900 then
AZ-204. Pick the provider the job actually uses — they are not interchangeable in
the way the *ideas* are.

### CKAD — Certified Kubernetes Application Developer

| | |
|---|---|
| **Cost** | ~$395 |
| **Time** | 6–8 weeks, and it needs a real cluster to practise on |
| **Value** | High where relevant, irrelevant otherwise |

**Entirely hands-on** — a live cluster, a terminal, two hours, real tasks. That
makes it much harder to pass by memorisation and much more credible as a result.

Not a junior certification. Listed because it is the one where the certificate
genuinely proves something, and because knowing *why* — the format — is a good
answer to "are certifications worth anything?".

---

## What each chapter already covers

If you take the OCP, this is where the preparation already exists.

| Exam area | Where |
|---|---|
| Types, operators, flow control | ch. 04 |
| Classes, interfaces, sealed types, records | ch. 04 · 30 |
| Generics and collections | ch. 05 |
| Streams and lambdas | ch. 05 |
| Concurrency | ch. 06 · plus virtual threads in `application.yml` |
| Exceptions | ch. 04 |
| `java.time` | ch. 20, via the injectable `Clock` |
| JDBC | ch. 07 — as JPA, so lighter than the exam wants |
| **Modules (JPMS)** | **nowhere** |
| **NIO.2 file I/O** | **nowhere** |
| Localisation | lightly, in the mail templates |

Two genuine gaps, both narrow, both a weekend each.

---

## The honest framing for a CV

**If you have one**, list it plainly with its date. It is a fact.

**If you do not**, do not apologise for it and do not say you are "planning to
take" one — everybody says that. The better answer to *"are you certified?"*:

> *"No. I have a repository I can walk you through instead — two implementations
> of the same API on Jakarta EE and Spring Boot, over one PostgreSQL schema, with
> the integration test that proves they map the same tables. If certification
> matters here I would take the OCP; I have looked at the objectives and the two
> areas I would need to study are modules and NIO.2."*

That answer does three things a certificate does not: it names something
checkable, it shows you have read the syllabus, and it identifies your own gaps
before they have to ask.

**And when an academy offers to pay for one — take it.** Free is a completely
different calculation from €250 of your own money, which is the entire reason
that line is on the advert.

---

## Still open

- [ ] Prices and exam codes are accurate as of writing and drift. Check
      `education.oracle.com` and the AWS certification pages before quoting them.
- [ ] The two OCP gaps — JPMS and NIO.2 — could each be a short appendix, and
      neither has anything to do with the rest of this project.
