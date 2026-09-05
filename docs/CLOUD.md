# Cloud, past the paragraph

**Backlog item B6.** Chapter 33 gives AWS, Azure and GCP one paragraph: *managed
service, object storage, identity and roles*. Those three ideas are the right
ones and they are not enough to act on. This is the rest.

> **What this document is not.** Nothing here has been deployed. Everything in
> `spring-service/` and `notification-service/` runs locally and is tested; this
> file is the design and the vocabulary for taking it to a provider, plus the
> parts of the application that would have to change. Where something is a claim
> rather than a demonstration, it says so.

---

## 1. The only three ideas that transfer

Console click-paths do not transfer between providers and are obsolete within a
year. These three do.

### Managed service

Someone else runs the database, patches it, backs it up and fails it over. It is
still PostgreSQL — the same wire protocol, the same SQL, the same JDBC driver.
`docker-compose.yml` becomes a connection string and nothing in
`application.yml` changes except `spring.datasource.url`.

| Provider | Managed PostgreSQL |
|---|---|
| AWS | RDS for PostgreSQL, or Aurora PostgreSQL |
| GCP | Cloud SQL for PostgreSQL |
| Azure | Azure Database for PostgreSQL |

**What you give up**: superuser. No `CREATE EXTENSION` for arbitrary extensions,
no filesystem access, no `pg_hba.conf`. Most projects never notice; the ones that
do, notice on the day they need an extension that is not on the allow-list.

**What actually changes in this codebase**: the connection URL, and the pool.
`maximum-pool-size: 10` per instance is fine for one process on a laptop. On a
managed instance with a hard connection limit (`db.t4g.micro` allows about 85),
three replicas at 10 each is 30 — fine; thirty replicas is not. **The pool size
is per instance and the limit is per database**, and multiplying them is the
arithmetic nobody does until connections start being refused.

### Object storage

S3 (AWS), Cloud Storage (GCP), Blob Storage (Azure). A flat namespace of
key → bytes, over HTTP, effectively unlimited, cheap.

The reason it matters reaches back into chapter 33's point about disposable pods:
**a container has no durable local disk**. Anything written to the filesystem is
gone at the next restart. So every uploaded file, generated PDF and export goes
to object storage instead — and the application stores the *key*, not the bytes.

This application has nothing to upload today. The first feature that does — a
student photo, an exported transcript — is where it appears.

**The one thing to know beyond that**: never proxy large files through your
application. Issue a *presigned URL* and let the client talk to the storage
service directly. Streaming a 200 MB file through a request thread ties up that
thread for the whole download, which is the pool-exhaustion failure from
chapter 25 arriving by a different route.

### Identity and roles

The idea worth carrying: **the application gets no password at all**.

Instead the compute resource (an EC2 instance, an ECS task, a Kubernetes service
account, a Cloud Run revision) is *assigned an identity*, and permissions are
attached to that identity. The SDK obtains short-lived credentials automatically
from the environment. Nothing is stored, so nothing can leak.

| Provider | The mechanism |
|---|---|
| AWS | IAM roles — instance profiles, or IRSA for EKS |
| GCP | Service accounts, Workload Identity |
| Azure | Managed Identities |

**Why this is the item to say in an interview**: it is the difference between
"we keep secrets in environment variables" and "there is no secret". Anyone can
describe the first. The second shows you know what the problem actually was.

**Database passwords are the awkward exception** — PostgreSQL wants a password,
and both AWS and GCP now support IAM authentication to their managed Postgres,
which removes it. Where that is not available, the password lives in Secrets
Manager / Secret Manager / Key Vault and is fetched at startup by the identity
above. It is never in an image, a repository, or a `docker-compose.yml`.

---

## 2. Where this application would go

Three realistic options, in increasing order of effort. The honest answer for a
project this size is the first.

### Option A — a container service (recommended)

**GCP Cloud Run** or **AWS App Runner** / **ECS Fargate**.

Push the image, set the environment variables, get a URL and TLS. No cluster to
operate, no nodes to patch, scales to zero when idle.

Cloud Run is the least painful for this specific application, because it takes a
container that listens on `$PORT` and needs nothing else. `enrollment-spring.jar`
already is that.

```bash
# The shape of it. Not run - see the note at the top of this file.
gcloud run deploy enrollment-spring \
  --source spring-service \
  --set-env-vars SPRING_PROFILES_ACTIVE=prod \
  --set-secrets SPRING_DATASOURCE_PASSWORD=enrollment-db-password:latest \
  --add-cloudsql-instances "$PROJECT:europe-west1:enrollment" \
  --region europe-west1
```

Note `SPRING_DATASOURCE_PASSWORD` — that is Spring Boot's **relaxed binding**
doing the work, and it is why no code or file has to change. The property
`spring.datasource.password` is settable as that environment variable, so a
secret store integrates with Spring Boot with no adapter at all. It is also the
answer to "how would you inject the database password in production".

**Two things about scale-to-zero that bite this application specifically:**

- **The scheduled job stops running.** `StatisticsRefreshJob` fires every ten
  minutes *while the process is alive*. On a platform that scales to zero, there
  is no process. The fix is an external trigger — Cloud Scheduler calling an
  endpoint — which is also the fix for the multi-instance duplicate-run problem
  that job already documents. One change, two problems solved.
- **Cold starts.** A JVM starting Spring Boot takes several seconds. Set a
  minimum instance count, or accept it. This is where GraalVM native images and
  Quarkus get their argument, and it is a real one for scale-to-zero workloads.

### Option B — Kubernetes

EKS, GKE or AKS. The right answer when there are many services and a platform
team; heavy machinery for three deployables.

Chapter 33 already covers the vocabulary — pod, deployment, service, ConfigMap,
Secret, liveness, readiness. What this repository can now supply that it could
not before are **the actual probe endpoints**:

```yaml
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8281 }
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8281 }
```

That split is the thing chapter 33 spends a page on and previously had nothing to
point at. Liveness must not check the database; readiness must. Point liveness at
`/actuator/health` — which *includes* the database — and one slow query restarts
the whole fleet.

### Option C — a VM

An EC2 instance or a Compute Engine VM running the same `docker compose` that
runs locally. Unfashionable, cheap, and exactly what `docs/DEPLOY-HETZNER.md`
already describes. For a project like this it is a defensible answer, and saying
so is more credible than reaching for Kubernetes.

---

## 3. What would have to change in the code

The useful part of this exercise. Deploying is not the work — these are.

| Change | Why | Status |
|---|---|---|
| Secrets from the environment, never a file | `application.yml` has `password: enrollment` in it | **Already works** via relaxed binding — no code change |
| `ddl-auto: none` in production | Even `validate` is a startup dependency on schema state; Flyway owns it | One line |
| Flyway as a separate step, not at startup | Chapter 29's argument: startup migration races across replicas | Already a Maven profile |
| Structured JSON logs | Cloud log aggregators parse JSON; a console pattern becomes one unsearchable blob | **Done** — `logback-spring.xml`, `json` profile |
| A `prod` profile | No Swagger UI, no `show-sql`, no demo seeder, no permissive CORS | **Not written** |
| Leader election for the scheduled job | Three replicas means three concurrent refreshes | **Not done** — documented in `StatisticsRefreshJob` |
| Externalised notification URL | `localhost:8282` is not a service address | Already a property; needs a real value |
| Graceful shutdown | Finish in-flight requests when the platform sends SIGTERM | `server.shutdown: graceful` — **not set** |

Four of the eight are already done, and that is the point of having built the
platform features first. The remaining four are small and specific, which is what
"cloud-ready" actually means — not a rewrite.

---

## 4. Terraform, at reading depth

You will meet it; you will not be asked to write it as a junior. Recognise the
shape:

```hcl
resource "google_sql_database_instance" "enrollment" {
  name             = "enrollment"
  database_version = "POSTGRES_16"
  region           = "europe-west1"

  settings {
    tier = "db-f1-micro"
    backup_configuration { enabled = true }
  }
}
```

**The one idea**: it is *declarative*. You describe the desired state; `terraform
plan` shows the difference between that and reality; `terraform apply` closes the
gap. Exactly the same mental model as a Kubernetes deployment, and as JPA's
`ddl-auto: validate` — describe what should be true, let a tool reconcile.

**The two things that make it worth having**: the plan is reviewable in a pull
request before anything changes, and the infrastructure is reproducible in a
second region or a second environment from the same file.

**The trap worth knowing about**: Terraform keeps *state* — a file recording what
it believes exists. If that state and reality diverge (someone changed something
in the console), the next `apply` will try to "fix" reality to match its record,
which can mean deleting things. "Never click in the console once you have
Terraform" is a real rule with a real reason.

Pulumi and AWS CDK are the same idea in a general-purpose language. CloudFormation
is the AWS-native one.

---

## 5. What to claim, and what not to

The interview answer, and it is a better one than a list of services:

> *"I have not run this in a cloud environment. I know the application would need
> its secrets from the environment rather than a file — which Spring Boot's
> relaxed binding already supports — migrations as a separate step rather than at
> startup, JSON logging, and leader election for the scheduled job, because it
> currently runs on every instance. Those are four specific changes and I can
> point at where each one goes."*

That is checkable, honest, and specific. Compare it with *"I know AWS"*, which
invites the next question.

The three ideas at the top of this document are worth genuinely knowing. The
click-paths are worth nothing, and pretending otherwise is caught in one
follow-up.

---

## Still open

- [ ] Actually deploy one of the two services somewhere and record what broke.
      Until that happens this document is a design, not experience, and it says
      so at the top.
- [ ] Write the `prod` profile — it is a small, concrete task and the table in
      section 3 is its specification.
- [ ] Add `server.shutdown: graceful` and a matching `terminationGracePeriod`.
