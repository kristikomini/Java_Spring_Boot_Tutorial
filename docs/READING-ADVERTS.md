# How to read a job advert

**Backlog item D4.** Chapter 36 does this for ordinary listings — the ranking,
the four cities, the requirement-to-chapter table. It does not cover the second
genre, which reads very differently and is answered very differently.

---

## Two genres, and they are not the same document

### The ordinary listing

> *"Conoscenza del linguaggio Java, SQL e database relazionali, Spring Boot,
> Git. Gradita esperienza con Angular."*

This is a **filter**. Somebody wrote down what the team actually uses, and HR will
screen against it. Chapter 36 is entirely about this genre, and its advice holds:
the requirements are roughly real, the "nice to have" list is genuinely optional,
and *"1 anno di esperienza"* is a filter against complete beginners rather than a
legal threshold.

### The academy or graduate programme

> *"Imparare a disegnare e sviluppare applicazioni utilizzando le tecnologie più
> avanzate, come Microservizi, Angular, API e Container… Metterti in gioco con
> Cloud, Blockchain, Intelligenza Artificiale, Machine Learning, Next Generation
> Mobile Apps e IoT."*

This is a **brochure**. Read the verbs: *imparare*, *metterti in gioco*,
*acquisire*, *conseguire*. Every one is about what you will **learn**, not what
you must already know.

The technology list is what the *organisation* does across all its clients — not
what you will be tested on, and not what you will work on. Nobody works on
blockchain and IoT and mobile and ML. **You will be staffed on one project, and
it will most likely be Java, Spring and SQL**, because that is what the volume of
work is.

---

## What actually gets tested

The screening for an academy is, in order:

1. **Degree and grade.** Frequently a hard filter — advert 1 says *minimum
   100/110* outright. This is the one requirement that is exactly as stated.
2. **An aptitude or logic test.** Common, and unrelated to the technology list.
3. **Java and OOP fundamentals.** Chapter 04, chapter 31.
4. **SQL.** A join with a `GROUP BY`, written by hand. Chapter 07, and chapter 38
   notes this is asked more often than any Java problem.
5. **Whether you can talk about something you built.** Chapter 37.
6. **English**, if the programme is international. Chapter 24.

**Blockchain does not appear on that list, and neither does IoT.** They are on the
advert because the organisation sells those services and the brochure is written
by marketing.

So the preparation for an academy advert listing eight advanced technologies is
**exactly the preparation chapter 36 already describes**. That is the single most
useful thing on this page.

---

## The question they will actually ask

> *"Which of these areas interests you most?"*

**Name one. Give a reason. Do not name six.**

Naming six reads as "I have not thought about it", and it invites a follow-up on
whichever one you know least. Naming one with a reason reads as somebody who
makes decisions.

A good answer, using what this repository actually contains:

> *"Data and analytics. I built a reporting layer on my project — window
> functions for ranking and year-on-year comparison, and a scheduled job
> materialising the aggregates into a separate table because running them against
> the transactional tables competes with the enrollment path. That taught me the
> OLTP/OLAP distinction properly rather than as a definition, and it is the area I
> would want to go deeper in."*

Specific, checkable, and it answers the question that was asked rather than
listing interests.

The same shape works for any of them. What makes it work is that **it names
something you did**, not something you would like to do.

---

## Is an academy a good first job?

Worth having a considered answer, because it is a real decision and the two sides
are both real.

**In favour**, genuinely:

- **The training is real.** Paid, structured, several weeks, with certifications
  frequently paid for — see `CERTIFICATIONS.md` on why free changes that
  calculation entirely.
- **They hire without experience by design**, which is the specific problem a
  junior has.
- **You see several codebases and several industries in two years**, which takes
  much longer at a product company.
- **The brand travels.** The big consultancies are recognised on a CV everywhere.

**Against**, equally genuinely:

- **You are staffed on what the client needs.** The eight technologies on the
  advert are the organisation's portfolio; your project is one of them, chosen by
  someone else, and it may well be maintaining a system older than you are.
- **Consultancy rhythm is different.** Billable hours, client sites, travel, and
  frequently less time for the quality work chapter 22 describes.
- **Junior salaries are lower** than at a product company, and the gap widens.
- **You may not own anything end to end**, which is the thing that makes the next
  interview easy.

**The honest summary**: an academy is a very good *first* job and a mediocre
*third* one. Most people do two years and move, and everyone involved knows that
is the arrangement.

---

## Reading any advert, in four questions

1. **Which genre is it?** Brochure verbs (*learn, grow, discover*) or filter
   nouns (*knowledge of, experience with*)? That decides everything else.
2. **What is the pairing?** Strip the list to the four things that recur —
   almost always language, framework, database, version control. Chapter 36's
   finding.
3. **Which requirements are hard?** Degree class, language, work authorisation,
   and location are usually literal. Years of experience and the "nice to have"
   list usually are not.
4. **What can I demonstrate rather than claim?** Every line you can answer with
   *"here is the file"* is worth ten you can only assert. That is chapter 37, and
   it is the whole reason this repository exists.

---

## The thing worth remembering

An advert listing blockchain, AI, mobile and IoT is **not asking you to know
them**. It is describing a company that does many things and inviting you to
learn one of them.

The preparation does not change. It is still Java, OOP, SQL, Git, and a project
you can walk somebody through — and being able to say *why* that is the
preparation, calmly, when confronted with a list of eight buzzwords, is itself a
signal that you have thought about the industry rather than been impressed by it.
