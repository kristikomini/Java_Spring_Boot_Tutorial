# Attaching a debugger

Twenty minutes of setup, then permanently useful. Reading that entities become
detached at commit is one thing; watching a variable change from a Hibernate
proxy to something that throws, while you step over a single closing brace, is
another.

---

## 1. Start WildFly with the debug agent

Stop the server, then start it with `--debug`:

```bash
standalone.bat --debug 8787 -Djboss.socket.binding.port-offset=200
```

That opens a JDWP listener on **8787**. The port offset does *not* apply to it —
`--debug` is a JVM flag, not a socket binding, so it stays 8787 regardless.

You will see this near the top of the output:

```
Listening for transport dt_socket at address: 8787
```

The server starts normally and serves traffic as usual. The agent only costs you
anything once a debugger actually attaches.

> **Suspend on startup?** `--debug` starts the server immediately.
> If you need to debug something that happens *during* deployment — `DataSeeder`,
> `ApplicationBootstrap`, a CDI producer — you need the JVM to wait for you:
> set `JAVA_OPTS` to include
> `-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:8787`
> and the server will not boot until your debugger connects.

---

## 2. Connect your IDE

### IntelliJ IDEA

1. **Run → Edit Configurations → + → Remote JVM Debug**
2. Host `localhost`, Port `8787`, leave the rest at defaults
3. Name it something like *WildFly 8787*, then **Debug**

The console should say `Connected to the target VM`.

### VS Code

Create `.vscode/launch.json` (VS Code offers to scaffold this for you):

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "Attach to WildFly",
      "request": "attach",
      "hostName": "localhost",
      "port": 8787,
      "projectName": "enrollment-service"
    }
  ]
}
```

Requires the *Extension Pack for Java*. Press **F5** to attach.

### Eclipse

**Run → Debug Configurations → Remote Java Application**, port `8787`,
connection type *Standard (Socket Attach)*.

---

## 3. The walkthrough worth doing first

This is the one that makes the persistence context concrete. It takes about five
minutes and is the reason to set any of this up.

**Set a breakpoint** on the first line of `enroll` in
`service/EnrollmentService.java` — the line reading `Instant now = clock.instant();`.

**Trigger it:**

```bash
curl -X POST http://localhost:8280/enrollment/api/enrollments \
     -H "Content-Type: application/json" \
     -d '{"studentId":102,"courseId":53}'
```

The curl will hang. That is correct — you have paused the thread that was serving
it. Now, in the debugger:

1. **Look at the call stack.** You did not write most of it. Count the frames
   between Undertow's HTTP handler and your method: filters, RESTEasy's
   dispatcher, the CDI client proxy, the `@Transactional` interceptor, the
   `@Loggable` interceptor. *That* is the container doing the work `provided`
   scope left out of your WAR.

2. **Step over** (F8 in IntelliJ) to the `studentRepository.findById` line and
   past it. Watch the `student` variable appear.

3. **Expand `student` in the variables panel.** Look at `enrollments`. Before you
   touch it, it is a `PersistentSet` marked *uninitialized* — a placeholder, not
   your data. Expand it in the debugger and the IDE forces the lazy load; watch
   the SQL appear in the server log at that exact moment. You just caused a
   database query by looking at a variable.

4. **Step over the prerequisite check.** Queries 5 and 6 from fieldbook chapter 2
   happen here, from what looks like an ordinary getter.

5. **Now the important part.** Step until you reach the closing brace of
   `enroll`, then step once more. You have just crossed the commit: the
   transaction ends and the persistence context closes. Every entity you were
   inspecting is now detached. Set a breakpoint in
   `api/mapper/EnrollmentMapper` and inspect the same objects — the collections
   that expanded happily a moment ago will now throw
   `LazyInitializationException` when the debugger tries to render them.

That single step over one brace is the whole of chapter 3, made visible.

---

## 4. Other breakpoints worth setting

| Where | What you learn |
|---|---|
| `common/logging/LoggingInterceptor.logInvocation` | Step into `ctx.proceed()` and land in the real method. This is how *every* interceptor works, `@Transactional` included. |
| `service/EnrollmentNotificationListener` (both observers) | Two breakpoints, one event. Watch the in-transaction one hit first and the `AFTER_SUCCESS` one hit after commit. |
| `api/exception/GenericExceptionMapper.toResponse` | Break the app with `./scripts/break.sh fetch-plan` and catch the exception on its way to becoming a 500. |
| `config/DataSeeder` (needs `suspend=y`) | Startup logic runs before you can attach otherwise. |
| `service/EnrollmentMaintenanceJob.reportStatistics` | Fires every five minutes with no request behind it. Note the call stack has no HTTP frames at all. |

---

## 5. Debugging the tests instead

Often faster, and it needs none of the above — no server, no attach. Just set a
breakpoint and run the test in debug mode from your IDE:

- `EnrollmentServiceTest` — the business rules, with mocked repositories.
- `CourseRepositoryIT` — real JPA against H2. Step through
  `entityManager.clear()` and watch what it does to the objects you are holding.

If you only ever debug one thing in this project, make it `CourseRepositoryIT`.
It is the shortest path to understanding detachment, and it runs in seconds.

---

## Troubleshooting

**`Connection refused` on 8787.** The server was not started with `--debug`.
Check the startup output for `Listening for transport dt_socket`.

**Breakpoints show as unverified / hollow.** The IDE compiled different bytecode
than the server is running. Run `mvn package`, let WildFly redeploy, reattach.

**The server seems frozen.** It is — you are stopped on a breakpoint on a request
thread. Other requests queue behind it. Resume, or disconnect the debugger.

**Everything is slow after disconnecting.** Remove any breakpoints that were set
inside frequently-called framework code; a hit-and-continue breakpoint still
costs a JVM round trip every time.
