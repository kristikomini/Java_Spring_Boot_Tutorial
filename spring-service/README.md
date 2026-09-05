# `spring-service` — the same application, on Spring Boot

Chapters 16–18 of the fieldbook teach Spring against a repository that did not
contain any. This module is that gap closed: a running Spring Boot application,
over the **same PostgreSQL schema**, exposing the **same API contract**, so the
Rosetta-stone table in chapter 18 becomes two files you can diff.

It is deliberately **not** a replacement for the Jakarta EE application, and not
a module of the root POM. Two independent builds, one repository:

```bash
mvn package                          # -> docker/deployments/enrollment.war  (WildFly)
```
```bash
mvn package -f spring-service/pom.xml   # -> spring-service/target/enrollment-spring.jar
```

---

## Run it with nothing installed

```bash
mvn spring-boot:run -f spring-service/pom.xml -Dspring-boot.run.profiles=demo
```

In-memory H2, seeded with five courses and a course deliberately one seat from
full so the 409 path is reachable. No PostgreSQL, no Docker, no migrations.
It is not a test of anything — see `application-demo.yml` — it is a way to see
the thing running in twenty seconds.

Then: <http://localhost:8281/swagger-ui.html>

## Run it against the real database

The database must be up first — both applications use it.

```bash
docker compose up -d postgres
```

Then, from the repository root:

```bash
mvn spring-boot:run -f spring-service/pom.xml
```

The API is at `http://localhost:8281/api` (the WAR is on `8280/enrollment/api`,
so both can run at once).

```bash
curl -s localhost:8281/api/courses?year=2025 | jq
```

If the schema is not there yet, apply the migrations from the root project:

```bash
mvn -Pflyway flyway:migrate
```

---

## The comparison, file by file

| Concern | Jakarta EE | Spring Boot |
|---|---|---|
| Entry point | none — WildFly is the process | [`EnrollmentSpringApplication`](src/main/java/it/unicam/cs/enrollment/spring/EnrollmentSpringApplication.java) |
| Config | `docker/wildfly/configure.cli` (server) | [`application.yml`](src/main/resources/application.yml) (application) |
| Entities | `domain/model/*.java` | [`domain/*.java`](src/main/java/it/unicam/cs/enrollment/spring/domain) — **identical annotations** |
| Repository | `AbstractJpaRepository` + 80-line class | [`CourseRepository`](src/main/java/it/unicam/cs/enrollment/spring/repository/CourseRepository.java) — an interface, no implementation |
| Service | `@ApplicationScoped` + `@Inject` | [`EnrollmentService`](src/main/java/it/unicam/cs/enrollment/spring/service/EnrollmentService.java) — `@Service`, same method bodies |
| Transactions | `jakarta.transaction.@Transactional` | `org.springframework...@Transactional` — same default rollback rule |
| Endpoints | `@Path` / `@GET` / `@PathParam` | [`CourseController`](src/main/java/it/unicam/cs/enrollment/spring/web/CourseController.java) — `@GetMapping` / `@PathVariable` |
| Errors | **six** `ExceptionMapper` classes | [`RestExceptionHandler`](src/main/java/it/unicam/cs/enrollment/spring/web/error/RestExceptionHandler.java) — **one** `@RestControllerAdvice` |
| Correlation id | `@Provider ContainerRequestFilter` | [`CorrelationIdFilter`](src/main/java/it/unicam/cs/enrollment/spring/web/filter/CorrelationIdFilter.java) — `OncePerRequestFilter` |
| Clock | `@Produces` in `ClockProducer` | `@Bean` in [`ClockConfig`](src/main/java/it/unicam/cs/enrollment/spring/config/ClockConfig.java) |
| Health | nothing | Actuator — `/actuator/health/{liveness,readiness}` |
| API docs | nothing | springdoc — `/swagger-ui.html`, `/v3/api-docs` |
| Metrics | nothing | Micrometer → `/actuator/prometheus` |
| Mapping | 4 hand-written mappers | one hand-written, one **MapStruct**-generated |
| Caching | nothing | `@Cacheable` + Caffeine, with eviction and hit-ratio metrics |
| Versioning | nothing | `/api/v1` and `/api/v2` served at once |
| Reporting | nothing | window functions, a materialised table, a chunked job |

### Where it is genuinely the same

`EnrollmentStatus`, the state machine, is byte-for-byte identical, because it is
pure Java. So is every JPA and Bean Validation annotation on the entities — those
are **specifications**, and Spring ships the same Hibernate that WildFly does.
That is the single most useful thing in this module: your mapping knowledge
transfers completely. What changes is who starts Hibernate, who opens the
transaction, and who hands you the `EntityManager`.

### Where it is genuinely different

- **Controller scope.** A JAX-RS resource is `@RequestScoped`; a
  `@RestController` is a **singleton**. Mutable controller fields are a race
  condition. This is the mistake most likely to be made by porting with
  find-and-replace.
- **Validation raises two exceptions.** A rejected `@RequestBody` gives
  `MethodArgumentNotValidException`; a rejected query parameter gives
  `ConstraintViolationException`. Handle one and half your 400s become 500s.
  JAX-RS has one exception for both.
- **Open Session In View** is on by default in Boot and is switched **off** here.
  See the long comment in `application.yml` for why.
- **Exception translation.** `@Repository` converts Hibernate/JDBC exceptions
  into Spring's `DataAccessException` family — which is why the handler catches
  `DataIntegrityViolationException` and `ObjectOptimisticLockingFailureException`
  rather than the JPA types.

---

## Tests — and what each one actually proves

```bash
mvn test    -f spring-service/pom.xml     # 60 tests, no Docker, ~35s
mvn verify  -f spring-service/pom.xml     # + 11 integration tests (needs Docker)
```

| Test | Starts | Proves | Cannot prove |
|---|---|---|---|
| [`EnrollmentServiceTest`](src/test/java/it/unicam/cs/enrollment/spring/service/EnrollmentServiceTest.java) | nothing — `new` + Mockito | the eight business rules, with a fixed `Clock` | that any JPQL is correct |
| [`CourseRepositoryTest`](src/test/java/it/unicam/cs/enrollment/spring/repository/CourseRepositoryTest.java) | `@DataJpaTest` + H2 | the queries parse and return the right rows | real PostgreSQL locking behaviour |
| [`CourseControllerTest`](src/test/java/it/unicam/cs/enrollment/spring/web/CourseControllerTest.java) | `@WebMvcTest` | status codes and **the JSON field names** | that the service works |
| [`PlatformFeaturesTest`](src/test/java/it/unicam/cs/enrollment/spring/PlatformFeaturesTest.java) | full context on H2 | caching, versioning, OpenAPI, metrics, **and that an unknown path is 404 not 500** | anything PostgreSQL-specific |
| [`ReportingTest`](src/test/java/it/unicam/cs/enrollment/spring/reporting/ReportingTest.java) | full context on H2 | the window functions and the batch job, with the numbers asserted | that the SQL runs on PostgreSQL |
| [`EnrollmentApiIT`](src/test/java/it/unicam/cs/enrollment/spring/EnrollmentApiIT.java) | everything + PostgreSQL 16 | the application | — |

**The integration test is the one that does not lie.** It runs the *real*
migrations from `../src/main/resources/db/migration` against a throwaway
PostgreSQL container, then starts with `ddl-auto: validate`. So it is also the
**drift detector** between the two applications: add a column to an entity here
without writing a migration, and the context fails to load.

It also contains the one assertion nothing else in the repository can make —
ten concurrent requests racing for two seats, released together by a
`CountDownLatch`, asserting that exactly two win. That is `SELECT … FOR UPDATE`
genuinely blocking, which H2 does not reproduce and mocks cannot.

Without a Docker daemon these tests **skip** rather than fail
(`@Testcontainers(disabledWithoutDocker = true)`). A test that cannot run is not
a test that failed, and a build that is red for environmental reasons teaches
people to ignore red builds.

---

## The one command worth remembering

```bash
mvn spring-boot:run -f spring-service/pom.xml -Dspring-boot.run.arguments=--debug
```

Prints the **conditions evaluation report**: every auto-configuration that
matched and why, and every one that did not and why not. It is what turns
"auto-configuration is magic" into a list you can read, and it answers most
"why is this not configured the way I expected" questions in seconds.
