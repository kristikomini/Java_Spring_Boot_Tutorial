# Deploying with Coolify

The server already runs [Coolify](https://coolify.io), so this is the path to
use. If you are starting from a bare server instead, read
[DEPLOY-HETZNER.md](DEPLOY-HETZNER.md) — it deploys the same application with
Caddy in front, by hand.

---

## 1. What Coolify is doing, and what it is not

Coolify is a self-hosted platform-as-a-service. Installed, it gives you a
Traefik reverse proxy holding ports 80 and 443, a web UI, and machinery that
turns a git repository into a running container.

It takes over exactly three things from
[`docker-compose.prod.yml`](../docker-compose.prod.yml):

| Job | Done by, on a bare server | Done by, here |
|---|---|---|
| TLS certificates | Caddy, via ACME | Coolify's Traefik, via ACME |
| Routing a hostname to a container | The `reverse_proxy` line in the Caddyfile | Traefik labels Coolify generates |
| Getting new code onto the server | `git pull && docker compose up` over SSH | Coolify pulls and rebuilds |

Everything else is unchanged, and that is the point worth noticing. The
application image ([`Dockerfile.prod`](../docker/wildfly/Dockerfile.prod)) and
the server configuration
([`configure-prod.cli`](../docker/wildfly/configure-prod.cli)) are used
verbatim. WildFly does not know or care which proxy is in front of it — it only
knows that *some* proxy terminates TLS and forwards `X-Forwarded-Proto`, which
is why `proxy-address-forwarding` in section 5 of that file matters here for
precisely the same reason it did with Caddy.

What Coolify does **not** do is make any of the decisions in that file for you.
It will happily deploy a stack with an exposed admin console and a plaintext
password.

---

## 2. Before you start

- **The DNS record exists.** An `A` record for the hostname, pointing at the
  server, set to **DNS only** if you use Cloudflare. Proxied (orange cloud)
  records break Traefik's certificate issuance the same way they break Caddy's:
  the ACME challenge never reaches your server.
- **Ports 80 and 443 are open** and Traefik is answering on them. If Coolify is
  running, they are.
- **The repository is pushed.** Coolify clones from GitHub, not from your
  laptop. Anything uncommitted does not deploy.

---

## 3. Create the application

Log in to Coolify — `http://YOUR_SERVER_IP:8000`.

1. **Project → New Resource.**
2. Choose the source:
   - **Public Repository** if the repo is public. Paste the HTTPS clone URL.
     Nothing to authorise, but pushes do not trigger a redeploy until you add
     the webhook Coolify shows you afterwards.
   - **GitHub App** if it is private, or if you want automatic redeploys on
     push without wiring a webhook by hand.
3. **Build Pack: `Docker Compose`.** Not Dockerfile, and not Nixpacks. The
   compose file defines two containers; the Dockerfile build pack would deploy
   only one and leave the application without a database.
4. Fill in:

   | Field | Value |
   |---|---|
   | Branch | `master` |
   | Base Directory | `/` |
   | Docker Compose Location | `/docker-compose.coolify.yml` |

   The compose location is combined with the base directory, and the extension
   must match exactly — Coolify will not find `.yaml` if the file is `.yml`.

5. **Continue.** Coolify parses the compose file and shows the two services it
   found, `wildfly` and `postgres`.

---

## 4. Set the domain

Because [`docker-compose.coolify.yml`](../docker-compose.coolify.yml) declares

```
- SERVICE_FQDN_WILDFLY_8080
```

Coolify knows the `wildfly` service is the one to publish, and that the proxy
should talk to its port 8080. It offers a domain field for it.

Set it to the full URL, **with the scheme**:

```
https://java.testdemo.it
```

`https://` is what tells Coolify to request a certificate. Entering the bare
hostname gets you a site on plain HTTP and no obvious explanation of why.

Leave `postgres` with no domain. It has no HTTP interface, and giving it one
would publish your database to the internet.

---

## 5. Deploy

Press **Deploy** and watch the log.

The first deploy is slow — Maven downloads the dependency tree inside the build,
which on a small server takes something like five minutes. Later deploys reuse
the BuildKit cache and are much faster.

In order, you are waiting for:

1. the build to finish and report an image;
2. `postgres` to pass its healthcheck;
3. `wildfly` to log `WFLYSRV0025: WildFly ... started`;
4. Coolify to mark the deployment healthy — this waits on the `wildfly`
   healthcheck, which has a 120-second `start_period`, so a couple of minutes
   of "unhealthy" at the start is expected and not a failure.

Traefik requests the certificate the first time someone asks for the hostname
over HTTPS, so the very first request may take a few seconds.

---

## 6. Verify

Not "it loads in a browser". Check the things that fail silently.

**The whole stack answers:**

```bash
curl -s https://java.testdemo.it/api/courses/open | head -c 400
```

JSON means the WAR deployed, the datasource resolved its environment
expressions, connected to PostgreSQL, and Hibernate found its schema. One
request exercises every layer.

**The certificate is real** — issued by Let's Encrypt, not Traefik's
self-signed default:

```bash
curl -sv https://java.testdemo.it/ 2>&1 | grep -i 'issuer\|subject:'
```

`CN=TRAEFIK DEFAULT CERT` means certificate issuance failed and Traefik fell
back. Check the domain has `https://` in Coolify, and that the DNS record is
not proxied.

**The session cookie is `Secure`.** This is the one that fails silently if
`proxy-address-forwarding` is not in effect. Register an account through the
site, sign in, and confirm in the browser's developer tools that the session
cookie shows **Secure**, **HttpOnly** and **SameSite=Strict**. If `Secure` is
missing, WildFly is not seeing `X-Forwarded-Proto` — see
[`configure-prod.cli`](../docker/wildfly/configure-prod.cli) section 5.

**Nothing else is exposed.** From your own machine:

```bash
nmap -Pn java.testdemo.it
```

22, 80, 443 and Coolify's own 8000 and 6001. Not 8080, not 9990, not 5432.

---

## 7. Updating

Push to `master`. If you connected via the GitHub App, Coolify redeploys on its
own; with a public-repository source, add the webhook Coolify gives you to the
repository's settings, or press **Redeploy** in the UI.

Expect about a minute of downtime while WildFly restarts. Coolify can do
zero-downtime deployments, but they require two containers of the application to
run simultaneously, which on a 4 GB server sharing space with Coolify itself is
a good way to meet the out-of-memory killer.

---

## 8. Backups

The database lives in a Docker volume managed by Coolify. It survives redeploys
and reboots. It does not survive deleting the resource, and it is not a backup.

Coolify's scheduled-backup feature applies to *managed database resources* — a
PostgreSQL created through Coolify's own UI. The database here is part of the
compose stack instead (deliberately: it is described in git next to the thing
that uses it), so that feature does not cover it. Use a scheduled task instead.

In Coolify: **Scheduled Tasks** on this resource, with container `postgres`:

```bash
pg_dump -U enrollment enrollment | gzip > /tmp/db-$(date +%F).sql.gz
```

That writes inside the container, which is only useful if you then copy it out —
so for anything you would genuinely miss, also enable **Hetzner's own server
backups** in their console. They are around €1 a month here and they snapshot
the whole disk, volumes included.

The other option, if the data ever matters more than the tidiness: move
PostgreSQL out of the compose file into a Coolify-managed database resource and
point `ENROLLMENT_DB_URL` at it. You lose the single-file description and gain
scheduled backups to S3.

---

## 9. Memory

This is the constraint most likely to bite. On the server you now have
Coolify itself, Traefik, PostgreSQL, WildFly with a 768 MB heap, and — during a
deploy — a Maven build that will happily use a gigabyte.

Check free memory in Coolify's server view before the first deploy. On a 4 GB
machine it will be tight but workable. If deploys get killed, in order of
preference:

1. Lower the heap: change `-Xmx768m` to `-Xmx512m` in the `JAVA_OPTS` line of
   [`docker-compose.coolify.yml`](../docker-compose.coolify.yml). WildFly runs
   in 512 MB for an application this size.
2. Add swap on the server — see [DEPLOY-HETZNER.md](DEPLOY-HETZNER.md) §5. Swap
   is insurance against the build spike, not a substitute for memory.
3. Build the image in GitHub Actions and have Coolify deploy a pre-built image
   from `ghcr.io` instead of building on the server.

---

## 10. When something is wrong

| Symptom | Almost always |
|---|---|
| **502** from Traefik | `wildfly` is not up, or not healthy yet. Check the container log in Coolify. |
| **404 on every path**, including `/` | WildFly is up but the deployment failed. In the container's terminal: `ls /opt/jboss/wildfly/standalone/deployments` — a `ROOT.war.failed` file contains the reason. |
| Certificate is `TRAEFIK DEFAULT CERT` | Issuance failed. The domain is missing `https://`, or the DNS record is proxied through Cloudflare, or DNS does not point here. |
| **`WFLYCTL0211`** / "cannot resolve expression" in the WildFly log | An environment variable the datasource or mail session needs is missing. The message names it. |
| The site is up, then intermittently unreachable | A custom `networks:` key was added to the compose file. Coolify's documentation is explicit about this; remove it. |
| Build killed, or exit code **137** | Out of memory. See §9. |
| Deploy succeeds, database is empty | Expected on a first deploy — `DataSeeder` inserts demonstration data only when the database is empty, and it is idempotent. |

---

## 11. What is still shaped like a development build

Unchanged from [DEPLOY-HETZNER.md](DEPLOY-HETZNER.md) §14, and worth repeating
because Coolify makes deploying easy enough to forget:

`persistence.xml` still has `schema-generation.database.action=update`, so
Hibernate alters the live schema at deploy time, and `hibernate.show_sql=true`,
so every statement is printed. Both are marked in that file's own comments as
production-forbidden. They are acceptable for a public demonstration of a
teaching project with no real data in it, and stop being acceptable the moment
that changes. The repository already contains Flyway migrations and a `flyway`
Maven profile for when it does.

`DataSeeder` inserts demonstration students and courses on first boot. That is
deliberate — it is what makes the deployed site show something — and it is the
reason not to put anything real in this database.
