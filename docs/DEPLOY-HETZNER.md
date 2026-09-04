# Deploying to Hetzner

From an empty Hetzner account to a working HTTPS URL, and what to do with it
afterwards.

This is a runbook, so it is written to be followed in order. It is also written
to be understood: every step that could be done a different way says why it is
done this way, because a deployment you cannot reason about is a deployment you
cannot fix at nine on a Sunday evening.

---

## 1. What you are building

```
                     the internet
                          │
                          │  443/tcp, 443/udp, 80/tcp
   ┌──────────────────────▼──────────────────────────────┐
   │  Hetzner Cloud Firewall     only 22, 80, 443 open   │
   └──────────────────────┬──────────────────────────────┘
                          │
   ┌──────────────────────▼──────────────────────────────┐
   │  one CX22 / CAX11 server, Ubuntu 24.04, Docker      │
   │                                                     │
   │   ┌─────────┐    ┌──────────┐    ┌──────────────┐   │
   │   │  caddy  │───▶│ wildfly  │───▶│  postgres    │   │
   │   │ :80/443 │    │  :8080   │    │   :5432      │   │
   │   │ TLS     │    │ no       │    │ no published │   │
   │   │         │    │ published│    │ port         │   │
   │   │         │    │ port     │    │              │   │
   │   └─────────┘    └──────────┘    └──────┬───────┘   │
   │                                         │           │
   │                                  ┌──────▼───────┐   │
   │                                  │ postgres-data│   │
   │                                  │ (volume)     │   │
   │                                  └──────────────┘   │
   └─────────────────────────────────────────────────────┘
```

Three containers, defined by [`docker-compose.prod.yml`](../docker-compose.prod.yml).
Two of them have no route from the internet at all: the only thing listening on
a public port is Caddy.

Compare this with [`docker-compose.yml`](../docker-compose.yml), the development
stack, which publishes the application on 8280, the admin console on 9990, the
database on 55433 and a mail catcher on 8225. Every one of those is a
convenience that is also a door, and the production file is mostly the story of
closing them. Read the header comment in that file for why this is a separate
file rather than a Compose override — the short version is that Compose merges
lists by appending, so an override file can add a published port but can never
remove one.

---

## 2. Before you start

You need three things.

**A Hetzner Cloud account.** Sign up at `console.hetzner.cloud`. New accounts
are sometimes asked for identity verification before the first server will
boot; it is usually same-day, but do not plan to finish this in the next
fifteen minutes if you have not signed up yet.

**A domain name.** Caddy obtains a certificate from Let's Encrypt, and Let's
Encrypt issues certificates for names, not for IP addresses. Any registrar will
do. A subdomain of something you already own is fine.

**An SSH key.** If you do not have one:

```bash
ssh-keygen -t ed25519 -C "hetzner"
```

Paste the contents of `~/.ssh/id_ed25519.pub` into the Hetzner console under
*Security → SSH keys* before creating the server. Adding it at creation time is
what makes the server key-only from its first boot, rather than password-based
for the few minutes it takes you to change it — and those few minutes are not
theoretical. A new IPv4 address gets its first SSH login attempt from a scanner
within about a minute.

---

## 3. Create the server

In the Hetzner console: *Servers → Add Server*.

| Setting | Choose | Why |
|---|---|---|
| Location | Falkenstein or Nuremberg | Both are in Germany, roughly 20–30 ms from northern Italy. Helsinki is equivalent. |
| Image | Ubuntu 24.04 | Long-term support until 2029. Debian 12 works identically. |
| Type | **CX22** (x86) or **CAX11** (ARM) | 2 vCPU / 4 GB either way. See the note below before choosing ARM. |
| Volume, Networks, Load balancer | none | |
| Public IPv4 | yes | The extra monthly charge is for this. IPv6-only is cheaper and will cost you an afternoon the first time something cannot reach you. |
| SSH key | the one you added | |
| Firewall | create one, see §4 | |

Roughly €4–5 per month all in, but check current pricing — it moves, and the
IPv4 address is billed separately from the server.

**On ARM.** The CAX servers are Ampere ARM64, and slightly cheaper for the same
memory. Everything in this repository is architecture-neutral except one thing:
the WildFly base image. Check it has an `arm64` manifest before you commit:

```bash
docker buildx imagetools inspect quay.io/wildfly/wildfly:41.0.1.Final-jdk21
```

If `linux/arm64` is not in the list, take the CX22. Nothing else cares — the
JDBC driver is pure Java, Maven emits identical bytecode, and `postgres:16-alpine`
and `caddy:2-alpine` are both multi-arch.

---

## 4. The firewall

Hetzner's Cloud Firewall runs **outside** the server, on their network. That is
its main virtue over `ufw` on the box: a rule that blocks port 9990 keeps
blocking it even if something on the server misconfigures itself, and it cannot
be switched off by anything you do inside the VM. Use both if you like; use
this one first.

Under *Firewalls → Create Firewall*, inbound rules:

| Protocol | Port | Source |
|---|---|---|
| TCP | 22 | your IP address, if it is stable; `0.0.0.0/0`, `::/0` if not |
| TCP | 80 | `0.0.0.0/0`, `::/0` |
| TCP | 443 | `0.0.0.0/0`, `::/0` |
| UDP | 443 | `0.0.0.0/0`, `::/0` |

Leave outbound unrestricted. Apply it to the server.

Port 80 is not optional even though the site is HTTPS-only: Let's Encrypt's
HTTP-01 challenge arrives on it, and Caddy also uses it to redirect anyone who
types the bare hostname. UDP 443 is HTTP/3 — leave it out and browsers quietly
fall back to TCP, so it is genuinely optional.

Note what is **not** in that table: 8080, 9990, 5432. If you ever find yourself
adding one of those to debug something, add it scoped to your own IP and remove
it the same day.

---

## 5. First login and basic hardening

```bash
ssh root@YOUR_SERVER_IP
```

### A non-root user

```bash
adduser --disabled-password --gecos "" deploy
mkdir -p /home/deploy/.ssh
cp /root/.ssh/authorized_keys /home/deploy/.ssh/authorized_keys
chown -R deploy:deploy /home/deploy/.ssh
chmod 700 /home/deploy/.ssh
chmod 600 /home/deploy/.ssh/authorized_keys
```

`--disabled-password` means this account has no password to guess; it is
reachable only with the key you just copied.

### Turn off root login over SSH

Modern Ubuntu reads `/etc/ssh/sshd_config.d/*.conf` before the main file, and
settings there win. Writing a drop-in is better than editing `sshd_config`,
because a distribution upgrade will not have opinions about your file:

```bash
printf 'PermitRootLogin no\nPasswordAuthentication no\nKbdInteractiveAuthentication no\n' > /etc/ssh/sshd_config.d/99-hardening.conf
systemctl restart ssh
```

**Before you close this session**, open a second terminal and confirm
`ssh deploy@YOUR_SERVER_IP` works. Locking yourself out of a server whose only
other access method you just disabled is a rite of passage worth skipping.
Hetzner does provide a web console for exactly this, but it is a slow way to
learn the lesson.

### Automatic security updates

```bash
apt-get update && apt-get install -y unattended-upgrades
```

On Ubuntu Server this activates itself for the `security` pocket. It is the
single highest-value thing on this page relative to effort: the vulnerabilities
that get small servers compromised are almost never novel.

### Swap

A 4 GB box running a JVM, PostgreSQL and a Maven build has moments where it
would like more. Without swap the kernel resolves those moments by killing
something, and what it kills is usually the JVM, because it is the largest.

```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

Swap is not a substitute for memory — a server that swaps continuously is a
server that needs a bigger plan. It is insurance against the spike.

---

## 6. Install Docker

From Docker's own apt repository, not Ubuntu's `docker.io` package, which lags
by a long way and does not ship the Compose v2 plugin this repository's
commands assume.

```bash
apt-get update
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

You will also see `curl -fsSL https://get.docker.com | sh` recommended. It works
and it is Docker's own script — but piping a URL into a shell means you have
authorised whatever that URL returns today, and Docker themselves describe it as
not for production. The six lines above are the same install with the trust
anchored in a signing key.

Let `deploy` use Docker:

```bash
usermod -aG docker deploy
```

Understand what that grants. The Docker socket can start a container that mounts
`/` and runs as root, so membership of the `docker` group is equivalent to
passwordless root on this machine. It is the normal arrangement and it is fine
here; it is not a security boundary, and you should not treat `deploy` as a
restricted account because of it.

Log out and back in as `deploy` for the group to apply:

```bash
ssh deploy@YOUR_SERVER_IP
```

---

## 7. DNS

At your registrar, point the name at the server:

| Type | Name | Value |
|---|---|---|
| A | `enrollment` (or `@`) | the server's IPv4 |
| AAAA | same | the server's IPv6 |

**The AAAA record is where first deploys usually fail.** Hetzner gives every
server an IPv6 address, and if you publish an AAAA record, Let's Encrypt will
prefer IPv6 for the validation connection. If the address is wrong, or the
firewall's `::/0` rules are missing, validation fails while the site is
perfectly reachable over IPv4 from your browser — so everything looks fine and
nothing works. Either set both records correctly, or set only the A record.

Confirm before continuing, from your laptop:

```bash
dig +short enrollment.example.com A
```

DNS propagation is usually seconds and occasionally an hour. Wait for it.

---

## 8. Configure

On the server, as `deploy`:

```bash
git clone https://github.com/YOUR_USER/YOUR_REPO.git enrollment
cd enrollment
cp .env.prod.example .env.prod
chmod 600 .env.prod
```

Generate a database password and put it in `.env.prod`:

```bash
openssl rand -base64 30
```

Then edit `.env.prod` and set, at minimum, `SITE_DOMAIN`, `ACME_EMAIL` and
`ENROLLMENT_DB_PASSWORD`. Leave `ENROLLMENT_MAIL_TRANSPORT=log` for now — see
§12.

The file explains each value. The one that bites later: PostgreSQL applies
`POSTGRES_PASSWORD` only when it initialises an empty data directory, so
changing it afterwards changes the datasource's idea of the password without
changing the database's. If you need to rotate it later, do it with `ALTER USER`
inside the container and update the file to match.

---

## 9. Deploy

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

That command is long enough to be worth an alias:

```bash
echo "alias dcp='docker compose --env-file .env.prod -f docker-compose.prod.yml'" >> ~/.bashrc && . ~/.bashrc
```

after which it is `dcp up -d --build`, `dcp logs -f`, `dcp ps`.

The first run builds the WAR from source inside the image — Maven downloads its
dependencies, which on a 2 vCPU server takes something like three to six
minutes. Subsequent builds reuse a BuildKit cache mount for `~/.m2` and are much
faster. Watch it:

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml logs -f
```

You are waiting for three things, in order: PostgreSQL reporting
`database system is ready to accept connections`; WildFly reporting
`WFLYSRV0025: WildFly ... started`; and Caddy reporting `certificate obtained
successfully`.

---

## 10. Verify

Not "load it in a browser and see if it looks right" — check the things that
fail silently.

**The application answers, over TLS:**

```bash
curl -s https://enrollment.example.com/api/courses/open | head -c 400
```

JSON means the WAR deployed, the datasource resolved its expressions, connected,
and Hibernate found its schema. That one request exercises the whole stack.

**The security headers are present:**

```bash
curl -sI https://enrollment.example.com | grep -i 'strict-transport\|x-content-type\|x-frame\|^server'
```

You should see the three headers from the Caddyfile, and no `Server:` line.

**The session cookie is marked `Secure`** — this is the one that fails silently
if `proxy-address-forwarding` is not set:

```bash
curl -si -X POST https://enrollment.example.com/api/fieldbook/auth/signin -H 'Content-Type: application/json' -H 'X-Fieldbook-Request: 1' -d '{"email":"nobody@example.com","password":"wrong"}' | grep -i 'set-cookie\|^HTTP'
```

A failed sign-in should not set a session cookie at all — that is the correct
answer here. To see the flags you need a real account: register one through the
site, sign in, and check in the browser's developer tools that the session
cookie shows **Secure**, **HttpOnly** and **SameSite=Strict**. If `Secure` is
missing, the proxy headers are not being trusted; see §13.

**HTTP redirects to HTTPS:**

```bash
curl -sI http://enrollment.example.com | head -3
```

**Nothing else is exposed.** From your laptop, not the server:

```bash
nmap -Pn enrollment.example.com
```

Expect 22, 80, 443 and nothing else. If `nmap` is not to hand,
`curl -m 5 http://enrollment.example.com:9990` timing out is a weaker but
sufficient check.

---

## 11. Updating

```bash
git pull
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

Compose rebuilds the image, then replaces the containers whose definition
changed. Expect roughly a minute of downtime while WildFly restarts. That is
acceptable for this; the machinery that avoids it (two application containers,
health-checked, cut over by the proxy) is worth learning and is not worth
building here.

Building on the server is the simplest thing that works, and it is what makes
the server need nothing but Docker. The next step up, once you are tired of
waiting for Maven on 2 vCPUs, is to build the image in GitHub Actions, push it
to `ghcr.io`, and have the server only pull — at which point deploying is a
`pull` and a `up -d`, and rolling back is pointing at the previous tag.

---

## 12. Mail

The stack deploys with `ENROLLMENT_MAIL_TRANSPORT=log`: messages are written to
the application log and delivered nowhere. Keep it that way until you have
thought about the fact that, on this application, anyone who can reach the
registration page can cause it to send mail to any address they type.

When you do want real delivery, you need a relay — Brevo, Mailgun, Postmark,
Amazon SES and Fastmail all have workable free or cheap tiers. Do **not** try to
send directly from the server: Hetzner blocks outbound port 25 on new accounts
by default, and even unblocked, mail from a fresh cloud IP with no SPF or DKIM
goes to spam essentially always.

Set `ENROLLMENT_SMTP_*` and `ENROLLMENT_MAIL_FROM` in `.env.prod`, publish the
SPF and DKIM records your relay gives you, then flip
`ENROLLMENT_MAIL_TRANSPORT=smtp` and redeploy. Keep `ENROLLMENT_MAIL_SUBJECT_PREFIX`
set: this is a teaching deployment of a fictional registrar, and the prefix is
what stops one of its messages being mistaken for correspondence from a real
university.

---

## 13. Backups

The compose file keeps the database in a named Docker volume, which survives
`down`, `up`, reboots and image rebuilds. It does not survive `down -v`, a
deleted server, or a corrupted disk.

**Hetzner backups** — enable them on the server in the console. They are
automatic snapshots of the whole disk at 20% of the server price, so roughly €1
per month here. This is the highest-value box to tick on the page.

**Logical dumps**, which are what you actually want when the problem is "I
deleted the wrong rows" rather than "the server is gone":

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml exec -T postgres pg_dump -U enrollment enrollment | gzip > ~/backup-$(date +%F).sql.gz
```

As a nightly cron entry for the `deploy` user (`crontab -e`):

```
0 3 * * * cd ~/enrollment && docker compose --env-file .env.prod -f docker-compose.prod.yml exec -T postgres pg_dump -U enrollment enrollment | gzip > ~/backups/db-$(date +\%F).sql.gz && find ~/backups -name 'db-*.sql.gz' -mtime +14 -delete
```

Note the escaped `\%` — cron treats an unescaped `%` as a newline, which is one
of the great time-wasting behaviours of Unix. Create `~/backups` first, and
copy one dump to your laptop occasionally: a backup that only exists on the
machine it is backing up is not a backup.

---

## 14. What is still shaped like a development build

Two settings inside the WAR are marked in their own comments as unsuitable for
production, and this deployment does not change them. That is a defensible
choice for a public demonstration of a teaching project with no real data in
it, and an indefensible one the moment that stops being true.

**`jakarta.persistence.schema-generation.database.action=update`**
([`persistence.xml`](../src/main/resources/META-INF/persistence.xml)) lets
Hibernate alter the live schema at deploy time to match the entities. It never
drops a column, cannot be reviewed before it runs, and has no rollback. The
repository already contains the alternative — Flyway migrations in
`src/main/resources/db/migration` and a `flyway` Maven profile. Moving over
means running `mvn -Pflyway flyway:migrate` against the server's database
through an SSH tunnel, then changing this value to `validate`, so that a
mismatch between entities and schema is a startup failure rather than a silent
`ALTER TABLE`.

**`hibernate.show_sql=true`** in the same file prints every statement Hibernate
generates. It bypasses the logging subsystem — so the INFO level set in
`configure-prod.cli` does not suppress it — and it is a real throughput cost.
Setting it to `false` is a one-line change, at the price of making the
development stack quieter too unless the value is made a build property.

There is also **`DataSeeder`**, which inserts demonstration students and
courses on first boot when the database is empty. That is deliberate here: it
is what makes the deployed site show something. It is also the reason not to
put anything real in this database.

---

## 15. When something is wrong

| Symptom | Almost always |
|---|---|
| Caddy returns **502** | WildFly is not up yet, or failed to boot. `dcp logs wildfly`. |
| **404 on every path**, including `/` | WildFly is up but the deployment failed. Look for a `.failed` file: `dcp exec wildfly ls /opt/jboss/wildfly/standalone/deployments` — the file contains the reason. |
| WildFly logs **`WFLYCTL0211`** or "cannot resolve expression" | A variable in `.env.prod` is missing or misspelled, or you forgot `--env-file`. The message names the expression. |
| Caddy logs **`could not get certificate`** | DNS. Usually a stale or wrong AAAA record (§7), or port 80 closed in the firewall. |
| Session cookie has no **`Secure`** flag | `proxy-address-forwarding` is off, or a `ports:` mapping was added to the wildfly service so it is no longer proxy-only. See `configure-prod.cli` §5. |
| Container killed, exit code **137** | Out of memory. Check `free -h`; confirm the swapfile from §5 is active with `swapon --show`. |
| Datasource errors on the **first** boot only | WildFly reached PostgreSQL before it finished initialising. The `depends_on: service_healthy` condition normally prevents this; a restart resolves it. |
| Everything worked, now the disk is full | `docker system df`, then `docker system prune -a`. Old images from previous builds accumulate; nothing removes them for you. |

To read the logs of one service:

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml logs -f wildfly
```

To get a shell inside a container:

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml exec wildfly bash
```

To reach the WildFly management console, which is deliberately not exposed:
tunnel to it rather than publishing it. From your laptop, forward a local port
to the server, then from the server into the container — or, more simply, run
`jboss-cli` inside the container:

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml exec wildfly /opt/jboss/wildfly/bin/jboss-cli.sh --connect
```

That works without a management user because it connects over the loopback
interface, where WildFly's local authentication mechanism applies. It is also
the reason the management port must never be published.

---

## 16. Tearing it down

Delete the server in the Hetzner console. Billing is hourly and stops when the
server is deleted, not when it is powered off — a stopped server still bills,
because it is still holding its disk and its IP.

Take a dump first if the data matters (§13), and note that snapshots and the
IPv4 address are billed separately and are not deleted with the server.
