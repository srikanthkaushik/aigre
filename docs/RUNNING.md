# AIGRE — Running the Application Locally

AIGRE is deployed for the NH Division of Motor Vehicles: the `dmv` domain profile, which
routes complaints to five DMV bureaus, against its own `aigre_dmv` database. The
numbered steps below are shared groundwork (Postgres, Ollama, backend, frontend) and
default to the original city build. **To run the DMV bureaus, do steps 1, 2 and 5 as
written, and follow [Running the DMV profile](#running-the-dmv-profile-nh-dmv-bureaus)
in place of steps 3 and 4.**

## Prerequisites

| Tool | Version used in development | Notes |
|---|---|---|
| JDK | 21 | Any Java 21 distribution; the shared dev machine used a Temurin/OpenJDK 21 install |
| Maven | 3.x, on `PATH` | `mvn -v` to check |
| Docker | any recent version | for the Postgres/pgvector container |
| Ollama | latest, running locally | default LLM provider — fully offline |
| Node.js | 22.14.0+ (specifically `^20.19.0 \|\| ^22.12.0 \|\| >=24.0.0`) | required by Angular CLI 21; check with `node -v` |
| npm | 10.x+ | ships with Node |

Optional: an Anthropic API key, if you want to run with `claude-sonnet-5` instead of
the local Ollama model (see [Switching providers](#switching-providers)).

---

## 1. Start PostgreSQL + pgvector

```
docker run -d --name aigre-pg \
  -e POSTGRES_DB=aigre \
  -e POSTGRES_USER=aigre \
  -e POSTGRES_PASSWORD=aigre_dev \
  -p 5434:5432 \
  -v aigre-pg-data:/var/lib/postgresql/data \
  pgvector/pgvector:pg16
```

Note the port: **5434**, not Postgres's default 5432 — chosen to avoid colliding with
other local projects. `application.yml`'s `spring.datasource.url` already points at
`jdbc:postgresql://localhost:5434/aigre`.

The schema (`src/main/resources/schema.sql`) runs automatically on every backend
startup (`spring.sql.init.mode: always`) — it's idempotent (`CREATE TABLE IF NOT
EXISTS`, `ON CONFLICT DO NOTHING` for the departments/SLA-policy seed rows), so this
is safe to leave as-is across restarts.

## 2. Start Ollama and pull the models

Install Ollama (https://ollama.com) if you don't have it, then:

```
ollama pull qwen2.5:7b
ollama pull qwen3:8b
ollama pull nomic-embed-text
```

Ollama should be listening at `http://localhost:11434` (its default) — this matches
`application.yml`'s `ollama.base-url`. Two chat models, not one: `qwen2.5:7b` handles RAG
rerank and the citizen chat's own responses (`ollama.chat-model`), `qwen3:8b` handles
grievance classification only (`ollama.classification-chat-model`) — a deliberately
slower, more-accurate model that's safe to use there because classification runs once
per grievance, not once per retrieved chunk the way rerank does. See `PROJECT.md`'s
"Per-purpose Ollama model split" writeup if you're curious why they're split rather than
sharing one model.

## 3. Run the backend

```
cd <path-to-your-clone>
mvn spring-boot:run
```

Starts on **port 8085**. Confirm with:

```
curl http://localhost:8085/actuator/health
```

**Employee sessions and restarts.** With no `SECURITY_JWT_SECRET` set, the backend logs a
WARN and signs tokens with a random key per run, so every restart logs employees out and
drops returning-citizen recognition. To keep sessions across dev restarts, set a key of
your own before starting the backend. Never commit one:

```
set SECURITY_JWT_SECRET=<output of: openssl rand -base64 32>
```

**Windows note**: `mvn spring-boot:run` spawns two `java.exe` processes (the Maven
launcher and the actual app). If you need to kill and restart it, find the real one
first:

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'com.aigre.AigreApplication' }
```

## 4. Seed data

> These steps seed the **city** build (database `aigre`). For the DMV bureaus, use
> [Running the DMV profile](#running-the-dmv-profile-nh-dmv-bureaus) instead.

Two independent seeding steps — do both for a fully populated dashboard/chatbot:

**a) Operational demo rows** (grievances in various states, plus 5 deliberate edge
cases for testing) — run once against the running Postgres container:

```
docker exec -i aigre-pg psql -U aigre -d aigre < test-data/sql/seed.sql
```

**b) The RAG policy corpus** — loads `test-data/documents/**` into pgvector: 118 files,
the original 108 for the six seeded departments plus `SHARED`, and 10 more under `DMV/`
and `PRD/` added by live department onboarding (`DEPARTMENT_ONBOARDING.md`). This is a
live embedding call per chunk, so it takes a few minutes and requires Ollama to be running.
The DMV and PRD *department rows* aren't in `schema.sql`, so on a fresh database their
documents load but those departments don't exist until onboarded (see `PROJECT.md`'s
"DMV/PRD missing from `schema.sql`" open item):

```
curl -s -X POST http://localhost:8085/auth/login -H "Content-Type: application/json" -d "{\"username\":\"ops.admin\",\"password\":\"Demo1234!\"}"
curl -X POST -H "Authorization: Bearer <ADMIN_TOKEN>" "http://localhost:8085/ingest/reset?confirm=true"
```

The reset is ADMIN-only: the first command logs in as the seeded `ops.admin` (step 4a
must have run) and prints a `token`; paste it in place of `<ADMIN_TOKEN>`.

This **wipes and reloads** the entire `rag_documents` table — safe to re-run any time
the corpus changes, but don't run it against a database you don't want reset.

> **Careful**: `RetrievalEvalTest` (see `TEST_SCENARIOS.md`) has a destructive
> `@BeforeEach` that wipes `rag_documents` down to 2 hardcoded fixture rows. If you run
> that test, re-run step 4b afterward to restore the full corpus.

**c) Employee login credentials** — step 4a's seed also creates 13 employee accounts
(2 per department, 1 AGENT + 1 SUPERVISOR, plus 1 cross-department ADMIN), all sharing
the same demo password. A real deployment would never share one password across
accounts; fine here since the point is exercising real Spring Security + JWT auth, not
credential hygiene.

| Username | Name | Department | Role |
|---|---|---|---|
| `priya.nakamura` / `marcus.webb` | Priya Nakamura / Marcus Webb | DOT | AGENT / SUPERVISOR |
| `lena.ortiz` / `grant.okafor` | Lena Ortiz / Grant Okafor | DPW | AGENT / SUPERVISOR |
| `a.sandoval` / `r.whitfield` | A. Sandoval / R. Whitfield | DHHS | AGENT / SUPERVISOR |
| `kayla.simmons` / `dennis.choi` | Kayla Simmons / Dennis Choi | DOE | AGENT / SUPERVISOR |
| `priscilla.adeyemi` / `tom.reilly` | Priscilla Adeyemi / Tom Reilly | DHUD | AGENT / SUPERVISOR |
| `nora.fitzgerald` / `sam.alvarez` | Nora Fitzgerald / Sam Alvarez | DEP | AGENT / SUPERVISOR |
| `ops.admin` | Ops Admin | *(none — all departments)* | ADMIN |

Password for all: `Demo1234!`

AGENT can view their department's queue read-only; SUPERVISOR can additionally resume
a paused review and mark grievances resolved/closed. Dashboard access is scoped
strictly to the logged-in employee's own department — there's no picker to switch
departments anymore (see `ARCHITECTURE.md`). ADMIN is the one exception: no
`department_id` of its own, so every dashboard tab (Pending Review, the queue, Trends)
shows every department at once, and it can act on any department's grievance the same
way a SUPERVISOR can within its own.

## 5. Run the frontend

```
cd frontend
npm install
npm start
```

This runs `ng serve`, defaulting to port 4200. If that port is already in use (e.g. by
another Angular project on the same machine), specify one explicitly:

```
npx ng serve --port 4300
```

API calls (`frontend/src/app/core/api.service.ts`) are relative URLs, proxied to the
backend by `frontend/proxy.conf.json` (wired into `ng serve` via `angular.json`'s
`serve.options.proxyConfig`) — so from the browser's point of view every request stays
on whichever port you served the frontend on; no CORS round-trip is actually involved
during normal `ng serve` development. Backend CORS (`com.aigre.auth.SecurityConfig`) is
still configured for `http://localhost:*` as a fallback for any other local port.

---

## Exposing the app to the internet (optional)

The frontend and backend can be served from **one origin** — useful for tunneling the
app out to the internet without exposing two ports, and it's how a real deployment would
ship anyway.

**a) Build the frontend and copy it into the backend's static resources:**

```
cd frontend
npx ng build
cd ..
rm -rf src/main/resources/static/*
cp -r frontend/dist/frontend/browser/* src/main/resources/static/
```

`com.aigre.config.SpaWebFluxConfig` serves these files and falls back to `index.html`
for any path that isn't a real static file (e.g. `/employee`, `/citizen/xyz`), so a
direct load or a browser refresh on an Angular client-side route works instead of
404ing. Restart the backend (`mvn spring-boot:run`) to pick up the new files — the whole
app is now reachable at **http://localhost:8085** alone; the separate `ng serve` step is
no longer needed for this.

> Re-run step (a) after any frontend change — the static copy is a snapshot, not live.

**b) Tunnel port 8085 out with Cloudflare Tunnel** (`cloudflared`, free, no port
forwarding or router changes, automatic HTTPS):

```
winget install --id Cloudflare.cloudflared -e
cloudflared tunnel --url http://localhost:8085
```

This prints a random `https://<random-words>.trycloudflare.com` URL — the whole app
(citizen portal, employee dashboard, chat) is reachable there, with the tunnel making
only outbound connections from your machine (nothing to open in a firewall/router). It's
an anonymous "quick tunnel": no Cloudflare account needed, but the URL changes every
time you restart `cloudflared`. For a stable URL on your own domain, use a
[named tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-apps)
instead, which requires a free Cloudflare account with that domain's nameservers.

---

## Splitting across two machines (Unraid deployment, optional)

Ollama needs a GPU to be usable — the rerank pass alone does up to `rag.retrieval.
initial-k` (15) LLM calls per chat question. If you have a second box (e.g. an Unraid
NAS) with RAM and a Docker host but **no GPU**, keep Ollama on the GPU machine and move
everything else — Postgres and the app itself (frontend baked in, same as the
single-origin setup above) — onto the second box as containers.

**What runs where:**

| | Machine A (GPU) | Machine B (Unraid / Docker host) |
|---|---|---|
| Ollama (`qwen2.5:7b`, `qwen3:8b`, `nomic-embed-text`) | ✅ | — |
| Postgres/pgvector | — | ✅ (container) |
| Spring Boot app + baked-in frontend | — | ✅ (container) |

**a) Expose Ollama on the LAN from Machine A** (Windows, elevated PowerShell):

```powershell
[Environment]::SetEnvironmentVariable("OLLAMA_HOST", "0.0.0.0:11434", "Machine")
# Then restart Ollama (quit + relaunch the tray app, or restart its service) for
# OLLAMA_HOST to take effect.
New-NetFirewallRule -DisplayName "Ollama LAN (Unraid only)" -Direction Inbound -Protocol TCP -LocalPort 11434 -RemoteAddress <unraid-ip> -Action Allow
```

Scope the firewall rule to Machine B's specific LAN IP, not `0.0.0.0/0` — Ollama has no
built-in auth. Verify from Machine B itself (not Machine A, which would succeed via
loopback regardless of the firewall rule):

```
curl http://<machineA-lan-ip>:11434/api/tags
```

A 200 listing `qwen2.5:7b`, `qwen3:8b`, and `nomic-embed-text` confirms both the bind
and the firewall rule are correct.

**Reserve both machines' IPs in the router (DHCP reservation).** `OLLAMA_BASE_URL`,
`SECURITY_CORS_ALLOWED_ORIGIN_PATTERNS` and the firewall rule all hardcode LAN IPs. If
Machine A's lease moves, the app fails with `java.net.NoRouteToHostException: Host is
unreachable` — on Machine B, `ip neigh show <machineA-ip>` reports `INCOMPLETE` (nothing
answers ARP at that address). Check `ipconfig` on Machine A before suspecting Docker.

**b) Start Postgres on Machine B and migrate the existing corpus/data into it.**

Postgres is the `postgres` service in `docker-compose.yml` (container name
`aigre-pg-unraid`). Copy just `docker-compose.yml` onto Machine B (no source checkout
needed — the app image comes from GHCR, step c), then from its folder there (Unraid
terminal), start only the database:

```
mkdir -p /mnt/user/appdata/aigre/postgres
docker compose up -d postgres
docker exec aigre-pg-unraid pg_isready -U aigre -d aigre
```

The data lives in a bind mount under `/mnt/user/appdata/aigre/postgres`, so Unraid's
appdata backup tooling covers it and it survives `docker compose down` and container
recreation. `5434:5432` is only for reaching it from outside Docker (pgAdmin, `psql`
from Machine A) — the app container talks to it as `postgres:5432` on the compose
network.

> If you previously started `aigre-pg-unraid` by hand with `docker run`, remove it
> first (`docker rm -f aigre-pg-unraid`) — the container name would otherwise clash.
> It used the same bind-mount path, so the restored data carries over and you can skip
> the dump/restore below. The old `aigre` network is no longer used
> (`docker network rm aigre`).

Then dump the existing database on Machine A:

```powershell
docker exec aigre-pg pg_dump -U aigre -d aigre --format=custom --file=/tmp/aigre.dump
docker cp aigre-pg:/tmp/aigre.dump ./aigre.dump
```

Transfer `aigre.dump` to Machine B (SMB share, `scp`, USB stick — whatever's available),
then restore it there:

```
docker exec aigre-pg-unraid psql -U aigre -d aigre -c "CREATE EXTENSION IF NOT EXISTS vector;"
docker cp ./aigre.dump aigre-pg-unraid:/tmp/aigre.dump
docker exec aigre-pg-unraid pg_restore -U aigre -d aigre --clean --if-exists --no-owner --no-privileges /tmp/aigre.dump
```

`--clean --if-exists` matters if the app container has **ever** started against this
database: `spring.sql.init.mode: always` runs `schema.sql` on every boot, which creates
the tables and seeds the six base departments — a plain `pg_restore` then fails on
existing objects and duplicate keys. Stop the app first (`docker compose stop app`).

Verify the restore before starting the app container:

```
docker exec aigre-pg-unraid psql -U aigre -d aigre -c "SELECT count(*) FROM rag_documents;"
docker exec aigre-pg-unraid psql -U aigre -d aigre -c "SELECT id FROM departments;"
```

Expect `rag_documents` ≈1369 on the original dev database (543 chunks from the base
corpus plus the DMV/PRD onboarded documents; 0 means the restore didn't run — chat still answers, but with `sources[]`
empty) and `departments` to include `DMV`/`PRD` alongside the six
`schema.sql`-seeded ones — those two only exist via live onboarding (see `PROJECT.md`'s
"DMV/PRD missing from `schema.sql`" open item), so a fresh `schema.sql`-only rebuild
would silently drop them; a full `pg_dump`/`pg_restore` carries them across intact.

**c) Build and push the app image (Machine A), then run on Machine B.** The image is
built on Machine A and pushed to GHCR; Unraid only pulls it. (Unraid's bundled Docker
lacks buildx 0.17+, so `docker compose build` can't run there.) On Machine A, from the
repo root, once logged in (`docker login ghcr.io` with a GitHub PAT that has
`write:packages`):

```cmd
for /f %s in ('git rev-parse --short HEAD') do set SHA=%s
docker build -t ghcr.io/srikanthkaushik/aigre-app:latest -t ghcr.io/srikanthkaushik/aigre-app:%SHA% .
docker push ghcr.io/srikanthkaushik/aigre-app:latest
docker push ghcr.io/srikanthkaushik/aigre-app:%SHA%
```

The compose file also runs the mock DMV embed-demo site (`test-data/embed-demo`, a
tiny nginx image) as the `embed-demo` service. Build and push it the same way, from
the repo root. Rebuild it only when `test-data/embed-demo` changes:

```cmd
docker build -t ghcr.io/srikanthkaushik/aigre-embed-demo:latest -t ghcr.io/srikanthkaushik/aigre-embed-demo:%SHA% test-data\embed-demo
docker push ghcr.io/srikanthkaushik/aigre-embed-demo:latest
docker push ghcr.io/srikanthkaushik/aigre-embed-demo:%SHA%
```

It's served at `http://<unraid-ip>:5500`, and its default AIGRE instance comes from
`AIGRE_API_ORIGIN` in compose (the app's browser-facing origin,
`http://<unraid-ip>:8087`). Register its own origin as a DMV embed origin on the
Unraid app once, the same way as the "Testing the embeddable chat widget" step a below
but against `http://<unraid-ip>:8087` with `{"origin": "http://<unraid-ip>:5500"}`.
Without that, the chat button appears but the panel stays blank. `aigre-embed-demo`
is a separate GHCR package, so it starts private too.

GHCR packages start **private**. Either make `aigre-app` public (GitHub → Packages →
aigre-app → Package settings → Change visibility), or log in on Machine B once with a
PAT that has `read:packages`: `docker login ghcr.io -u srikanthkaushik`.

On Machine B, copy `.env.example` next to `docker-compose.yml` as `.env` and fill it
in: `AIGRE_OLLAMA_BASE_URL` (Machine A's Ollama), `AIGRE_LAN_ORIGIN` (Machine B's own
`http://<unraid-ip>:8087`) and `AIGRE_JWT_SECRET` (step d). With Unraid's Compose
Manager plugin, paste the same lines into the stack's **Edit Stack → Env File**
instead. Then (the app waits on the `postgres` healthcheck via `depends_on`):

```
docker compose pull
docker compose up -d
```

`docker compose` stops with `required variable ... is missing a value` until every
required `.env` value is set. That's deliberate: the file holds no personal defaults.

To deploy a new build: rebuild and push on Machine A, then `docker compose pull &&
docker compose up -d` on Machine B. Unraid's Compose Manager plugin works against this
file as-is. The image is `linux/amd64`, built on Machine A's Docker Desktop, which
matches Unraid.

The app is then reachable at `http://<unraid-ip>:8087` (host port 8087, since 8086 was taken on Unraid; the container still listens on 8085) — `application.yml`'s own
defaults (`localhost` Postgres/Ollama) are untouched, so Machine A's existing `mvn
spring-boot:run` dev workflow keeps working exactly as documented in steps 1–5 above,
completely independent of this deployment.

> **Why `AIGRE_LAN_ORIGIN` is required.** Compose feeds it into
> `SECURITY_CORS_ALLOWED_ORIGIN_PATTERNS`. Browsers send an `Origin`
> header for the Angular build's `<script type="module">` requests even on a genuinely
> same-origin load. Without Machine B's own origin in that list, Spring
> Security's CORS filter 403s those requests before any `permitAll` rule is reached and
> the page loads blank — the same bug class already hit and fixed for the Cloudflare
> tunnel case above, this time triggered by a LAN IP origin instead of a
> `trycloudflare.com` one.

**d) JWT secret (required).** The app service reads `SECURITY_JWT_SECRET` from
`AIGRE_JWT_SECRET` in `.env`, and `docker compose` refuses to start without it.
Without a configured key the app would fall back to a random key per boot, logging
every employee out on each container restart. No key is committed: this repo is
public, and an older committed value is compromised, so never reuse it. Generate one:

```
openssl rand -base64 32
```

**e) Exposing the embed demo on your own domain (optional, testing only).** One named
Cloudflare tunnel publishes two stable hostnames. The app needs its own hostname,
because the browser loads `embed.js` and the chat iframe from the app's origin, not the
demo's. The tunnel exposes **the whole app**, including the employee dashboard and
admin API.

*In the Cloudflare dashboard:* Zero Trust → Networks → Tunnels → **Create a tunnel** →
Cloudflared, name it (e.g. `aigre-unraid`), and copy the connector token from the
install command it shows. Under the tunnel's **Published application routes** (older
dashboards: **Public hostnames**), add:

| Subdomain | Service |
|---|---|
| `dmv-demo.<domain>` | `HTTP` → `embed-demo:80` |
| `aigre.<domain>` | `HTTP` → `app:8085` |

The service addresses are compose service names, resolved from inside the `cloudflared`
container on the compose network. Cloudflare creates the DNS records itself.

Gotchas from the first setup:
- **The dashboard shows an install command, not a token.** Pick the Docker tab and copy
  the `eyJ...` string after `--token`. Don't run the command: a standalone container
  can't resolve `embed-demo` or `app`. The dashboard waits for the compose `tunnel`
  service to connect before you can continue.
- **The routes tab is called "Published application routes"** in newer dashboards
  ("Public Hostname" in older ones). The single "Service URL" field needs the scheme:
  `http://embed-demo:80`. If it rejects container names, use `http://<unraid-ip>:5500`
  and `http://<unraid-ip>:8087`. Routes under "Private networks" create no public DNS.
  `NXDOMAIN` for the subdomain means no route was saved.
- **Unraid's Compose Manager plugin keeps its own `.env`.** Set it via the stack's
  **Edit Stack → Env File**, or in
  `/boot/config/plugins/compose.manager/projects/<stack>/.env`. If the stack logs say
  `required variable AIGRE_... is missing a value`, the plugin isn't seeing it.

*On Machine B:*

```
# 1. Set these two in .env (or the Compose Manager Env File); both are in .env.example:
#      CLOUDFLARE_TUNNEL_TOKEN=<token from the dashboard>
#      AIGRE_APP_PUBLIC_ORIGIN=https://aigre.<domain>

# 2. Allow the demo's public origin to frame DMV's chat (admin login on the LAN).
read -rsp "ops.admin password: " PW; echo
TOKEN=$(curl -s -X POST http://localhost:8087/auth/login -H "Content-Type: application/json" \
  -d "{\"username\":\"ops.admin\",\"password\":\"$PW\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s -X POST http://localhost:8087/admin/departments/DMV/embed-origins \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"origin":"https://dmv-demo.<domain>"}'

# 3. Recreate app + embed-demo with the public origin, and start the tunnel.
docker compose --profile tunnel up -d
docker logs aigre-tunnel 2>&1 | grep -i "registered tunnel connection" | head -2
```

Testers open `https://dmv-demo.<domain>`. No `?api=` is needed, because the page
defaults to `AIGRE_APP_PUBLIC_ORIGIN`. Stop the tunnel with
`docker compose --profile tunnel stop tunnel`.

**Before sharing the URL:** replace the shared seeded password (`Demo1234!`) on every
employee account. `seed.sql` isn't re-run at boot, so the change persists:

```
read -rsp "New employee password: " PW; echo
HASH=$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 "" "$PW" | tr -d ':\n')
docker exec aigre-pg-unraid psql -U aigre -d aigre \
  -c "UPDATE department_employees SET password_hash = '$HASH' WHERE username IS NOT NULL;"
```

With stable hostnames you can also put Cloudflare Access (Zero Trust → Access →
Applications) in front of the app's employee and admin paths. Don't put it on the whole
app hostname: the chat iframe would then need an Access cookie on a third-party origin,
which browsers increasingly block. Every public chat message runs on Machine A's GPU,
and there is no rate limiting.

---

## Running the DMV profile (NH DMV bureaus)

The `dmv` profile routes complaints to five NH Division of Motor Vehicles bureaus instead of
the city departments (see `ARCHITECTURE.md` "Domain profiles"). It's the deployment the
README and `APP_WALKTHROUGH.md` describe. It uses its **own database**, so the city data
is untouched and you can switch between the two.

**Locally (CMD, with `aigre-pg` and Ollama running):**

```
docker exec aigre-pg psql -U aigre -d aigre -c "CREATE DATABASE aigre_dmv"
docker exec aigre-pg psql -U aigre -d aigre_dmv -c "CREATE EXTENSION IF NOT EXISTS vector"

set SPRING_PROFILES_ACTIVE=dmv
set SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/aigre_dmv
mvn spring-boot:run
```

The first boot creates the schema and the five bureaus. Then, in a second window:

```
docker exec -i aigre-pg psql -U aigre -d aigre_dmv < test-data/dmv/sql/seed.sql
curl -s -X POST http://localhost:8085/auth/login -H "Content-Type: application/json" -d "{\"username\":\"ops.admin\",\"password\":\"Demo1234!\"}"
curl -X POST -H "Authorization: Bearer <ADMIN_TOKEN>" "http://localhost:8085/ingest/reset?confirm=true"
```

The seed adds 2 employees per bureau plus `ops.admin` and 8 sample grievances. Every
account's password is `Demo1234!` (replace it before exposing the deployment — step e of
the two-machine setup):

| Username | Name | Bureau | Role |
|---|---|---|---|
| `erin.callahan` / `victor.osei` | Erin Callahan / Victor Osei | `REG` Registration | AGENT / SUPERVISOR |
| `hannah.pruitt` / `luis.ferreira` | Hannah Pruitt / Luis Ferreira | `DL` Driver Licensing | AGENT / SUPERVISOR |
| `maya.lindqvist` / `owen.brightwater` | Maya Lindqvist / Owen Brightwater | `FR` Financial Responsibility | AGENT / SUPERVISOR |
| `priscilla.moreau` / `dev.raman` | Priscilla Moreau / Dev Raman | `TITLE` Title & Anti-Theft | AGENT / SUPERVISOR |
| `colleen.shaw` / `tomas.novak` | Colleen Shaw / Tomas Novak | `DEALER` Dealers & Inspections | AGENT / SUPERVISOR |
| `ops.admin` | Ops Admin | *(none — all bureaus)* | ADMIN |

Roles work as in step 4c. A complaint too vague to assign any bureau has no department,
so it appears only in `ops.admin`'s Pending Review queue. Ingestion loads the 16 rule chapters in `test-data/dmv/documents` (see
`test-data/dmv/corpus-manifest.md`). `curl http://localhost:8085/departments` should list
`DEALER`, `DL`, `FR`, `REG` and `TITLE`, and the UI should say "Bureau". Unset both
variables to go back to the city.

**Adding documents later:** drop files into a bureau's folder under
`test-data/dmv/documents/` (any format Tika reads), note them in the manifest, and re-run
the ingest call above.

**On the two-machine deployment (Machine B, bash).** Run these from the stack's
directory, so that `docker compose` reads the same `.env` that Compose Manager uses
(`/boot/config/plugins/compose.manager/projects/<stack>/`).

1. **Rebuild and push both images on Machine A** (step c). The app and the embed demo
   both changed.
2. **Replace the stack's `docker-compose.yml`** with the current one, via Edit Stack →
   Compose File or from the repo. Keep reading only once
   `grep -nE "AIGRE_PROFILE|AIGRE_DB_NAME|AIGRE_EMBED_DEPARTMENT" docker-compose.yml`
   prints three lines. An older compose file silently ignores the three settings below,
   and the app stays on the city profile and the `aigre` database.
3. **Add these to the Env File:**
   ```
   AIGRE_PROFILE=dmv
   AIGRE_DB_NAME=aigre_dmv
   AIGRE_EMBED_DEPARTMENT=
   ```
4. **Create the database, then pull and recreate the stack:**
   ```
   docker exec aigre-pg-unraid psql -U aigre -d aigre -c "CREATE DATABASE aigre_dmv"
   docker exec aigre-pg-unraid psql -U aigre -d aigre_dmv -c "CREATE EXTENSION IF NOT EXISTS vector"
   docker compose pull app embed-demo
   docker compose --profile tunnel up -d
   ```
5. **Check that the app booted the profile against the new database:**
   ```
   docker inspect aigre-app --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E "SPRING_PROFILES_ACTIVE|SPRING_DATASOURCE_URL"
   curl -s http://localhost:8087/app-config
   docker exec aigre-pg-unraid psql -U aigre -d aigre_dmv -c "SELECT id FROM departments ORDER BY id;"
   ```
   - **Expected:** `SPRING_PROFILES_ACTIVE=dmv`, a URL ending in `/aigre_dmv`, `"unitLabel":"Bureau"`
     and the five bureaus.
   - **Problem signs and fixes:**
     - **No `SPRING_PROFILES_ACTIVE` line at all:** the compose file is still the old one
       (go back to step 2).
     - **`/aigre_dmv` but city wording, or six city departments:** the city profile booted
       against the DMV database. Stop the app, drop and recreate `aigre_dmv` (step 4), fix
       the env, and recreate the stack.
     - **`relation "departments" does not exist`:** the app hasn't booted against
       `aigre_dmv` yet.
6. **Load the demo seed, and replace its password in the same sitting.** The seed gives
   every account `Demo1234!`, and the tunnel may already be public. The seed is read from
   the app image itself, so it matches the deployed version. `-1` with `ON_ERROR_STOP`
   makes the load all-or-nothing, and re-running it is harmless (`ON CONFLICT DO NOTHING`).
   ```
   docker exec aigre-app cat /app/test-data/dmv/sql/seed.sql \
     | docker exec -i aigre-pg-unraid psql -U aigre -d aigre_dmv -v ON_ERROR_STOP=1 -1

   read -rsp "New employee password: " PW; echo
   HASH=$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 "" "$PW" | tr -d ':\n')
   docker exec aigre-pg-unraid psql -U aigre -d aigre_dmv \
     -c "UPDATE department_employees SET password_hash = '$HASH' WHERE username IS NOT NULL;"

   docker exec aigre-pg-unraid psql -U aigre -d aigre_dmv \
     -c "SELECT coalesce(department_id,'(admin)') AS bureau, count(*) FROM department_employees GROUP BY 1 ORDER BY 1;" \
     -c "SELECT count(*) AS grievances FROM grievances;"
   ```
   - **Note `-d aigre_dmv`:** step e's command targets the city database.
   - **Expected:** 2 employees per bureau, 1 admin, and 8 grievances (G0001–G0008). New
     complaints continue from G0009.
7. **Build the rule corpus** (16 chapters, about 3,310 chunks, embedded on Machine A's
   GPU; allow several minutes). Use the new password:
   ```
   read -rsp "ops.admin password: " PW; echo
   TOKEN=$(curl -s -X POST http://localhost:8087/auth/login -H "Content-Type: application/json" \
     -d "{\"username\":\"ops.admin\",\"password\":\"$PW\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
   curl -s --max-time 1800 -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8087/ingest/reset?confirm=true"
   docker exec aigre-pg-unraid psql -U aigre -d aigre_dmv -c "SELECT count(*) FROM rag_documents;"
   ```
   If the count is zero, `docker logs aigre-app --tail 50` usually shows that Ollama at
   `AIGRE_OLLAMA_BASE_URL` was unreachable.
8. **Register the demo site's origin, in the same shell so `$TOKEN` is still set.**
   Any bureau will do. With an empty `AIGRE_EMBED_DEPARTMENT` the demo widget is
   agency-wide, so it can be framed by every registered origin. Origins registered in the
   city database don't carry over.
   ```
   curl -s -X POST http://localhost:8087/admin/departments/REG/embed-origins \
     -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
     -d '{"origin":"https://dmv-demo.<domain>"}'
   ```

**Switching back to the city:** clear `AIGRE_PROFILE` and `AIGRE_DB_NAME`, set
`AIGRE_EMBED_DEPARTMENT=DMV`, and run `docker compose --profile tunnel up -d`. Both
databases keep their data and passwords. A later corpus reset only rebuilds
`rag_documents`, and `seed.sql` never runs at boot.

**Measuring it:** the DMV evals run against the DMV database:

```
mvn test -Dtest=ComplaintEvalHarnessTest,DmvRagEvalSuiteTest -Dspring.profiles.active=dmv -Dspring.datasource.url=jdbc:postgresql://localhost:5434/aigre_dmv -Daigre.eval.complaints=test-data/dmv/grievances/eval-complaints.jsonl
```

---

## Testing the embeddable chat widget (optional)

The citizen portal's floating chat can also be embedded on an *external* website via
one `<script>` tag (`frontend/public/embed.js`). Under the `dmv` profile the widget can
be **agency-wide** (no `data-department`: answers from every bureau's rules) or scoped to
one bureau (`data-department="TITLE"`: answers only from that bureau's rules). Under the
city profile it must name a department. Trying this locally means running **two
separate "apps"** at once: the main AIGRE app and a second, throwaway static site
standing in for "the agency's existing website."

`test-data/embed-demo/index.html` is a ready-made stand-in for that second site — a
locally-styled (not real) NH DMV–style page that already includes the embed script. Its
`<meta name="aigre-department" content="DMV">` names the department the city build
onboarded. **Under the `dmv` profile, edit that to `content=""`** (agency-wide) or to a
bureau id: there is no `DMV` department in the DMV database, so the widget would stay
blank (`frame-ancestors 'none'`). The Docker image rewrites the meta from
`AIGRE_EMBED_DEPARTMENT`, so this only matters when serving the file locally.

**a) With AIGRE running, register an embed origin as an admin.** Embedding is
allowlisted per bureau (department), not open to any site that includes the script —
nothing renders until its origin is registered. An agency-wide widget may be framed by
an origin registered for any bureau:

```
curl -s -X POST http://localhost:8085/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"username\":\"ops.admin\",\"password\":\"Demo1234!\"}"
```

Grab the `token` field from the response, then:

```
curl -s -X POST http://localhost:8085/admin/departments/REG/embed-origins ^
  -H "Content-Type: application/json" ^
  -H "Authorization: Bearer <TOKEN>" ^
  -d "{\"origin\": \"http://localhost:5500\"}"
```

For a bureau-scoped widget, register the origin under that bureau's id instead. On the
city build, use `DMV` (the onboarded department the demo page names).

> **Load the demo page as `http://localhost:5500`, not `http://127.0.0.1:5500`.**
> `http-server`'s own startup banner prints `127.0.0.1` as one of its "Available on"
> URLs, but browsers treat `localhost` and `127.0.0.1` as different origins even
> though they're the same machine — only the exact origin you registered above will
> be allowed to frame the widget. If you want both to work, register a second
> `embed-origins` entry for `http://127.0.0.1:5500` the same way.

**b) Serve the demo page on a different port than AIGRE's** (it has to be a different
origin for this to actually test anything — same-origin embedding is trivial and proves
nothing about the CORS/framing story):

```
cd test-data\embed-demo
npx http-server -p 5500
```

To point the demo at a different AIGRE instance, such as the Unraid deployment, pass its
origin as `?api=`: `http://localhost:5500/?api=http://<unraid-ip>:8087`. With no `?api=`
it defaults to `http://localhost:8085`. The demo page's own origin, `http://localhost:5500`,
must be registered (step a) on *that* instance.

**c) Open `http://localhost:5500` in a browser.** A floating chat button appears
bottom-right — that's `embed.js`, injected by the demo page's own `<script>` tag, not
part of AIGRE's own frontend. Click it, ask a DMV question (e.g. *"What happens if my
car fails its state inspection?"*) and confirm it answers and cites Saf-C chapters. To
check scoping, set the meta to `TITLE`, register the origin under `TITLE`, and ask a
question another bureau owns (e.g. *"What do I need to restore my license after a
suspension?"*): it should **not** cross-contaminate — an honest "I don't know" instead,
or a real MCP tool call if the question references a specific grievance ID (scoping
only restricts the RAG corpus, not tool-calling — see `PROJECT.md`).

**To see the allowlist actually being enforced**, serve the same demo page on a
*different*, unregistered port instead (e.g. `npx http-server -p 5501` from the same
`test-data/embed-demo` directory) and open that — the floating button still appears (it's injected
client-side, unconditionally), but the chat panel stays blank when clicked. Check the
browser devtools console for a `Content-Security-Policy: frame-ancestors` violation —
the browser itself refuses to render AIGRE's page in the iframe; nothing on AIGRE's
side needs to detect or reject the unregistered origin at request time.

Full design writeup — why an iframe instead of a raw cross-origin `fetch`, why
`Content-Security-Policy: frame-ancestors` instead of CORS, why the origin allowlist
instead of an open embed — is in `PROJECT.md`'s "Floating chat widget + embeddable,
department-scoped chat" section.

---

## Email ingestion (optional)

A second inbound channel alongside the portal: `com.aigre.email.EmailGrievancePoller`
polls a monitored IMAP mailbox on a schedule and feeds each unread message through the
exact same `GrievanceWorkflowService.start(...)` entry point the portal uses -- same
classification, duplicate detection, human-review pause, and SLA computation. Off by
default (`email.enabled: false`) -- the app runs fine with nothing configured here.

To try it against a real mailbox (e.g. a Gmail account with an
[app password](https://myaccount.google.com/apppasswords), since Google no longer
accepts your regular password for IMAP), set every `email.*` property via environment
variables (Spring's relaxed binding: `email.imap.host` → `EMAIL_IMAP_HOST`, etc.) rather
than editing `application.yml` directly — that keeps a real mailbox's address and
password out of git entirely, since `application.yml` is checked in and its committed
defaults (`email.enabled: false`, everything blank) should stay inert for anyone else
who clones the repo:

```
setx EMAIL_APP_PASSWORD "your-16-char-app-password"
```
(a **persistent** env var, in your own shell, never pasted into a chat/AI session —
`setx` needs a new terminal to take effect; already-running shells/processes won't see
it). Then launch the backend with the rest set for that process only:

```
set EMAIL_ENABLED=true
set EMAIL_IMAP_HOST=imap.gmail.com
set EMAIL_IMAP_USERNAME=your-address@gmail.com
set EMAIL_IMAP_PASSWORD=%EMAIL_APP_PASSWORD%
mvn spring-boot:run
```

Send a plain-text email to that address; within one poll interval it appears as a new
grievance with `channel = EMAIL`, visible on the employee dashboard the same way a
portal submission is -- including landing in Pending Review if the classifier isn't
confident. A message that fails to ingest is moved to a `Failed` IMAP folder rather than
retried forever on every poll.

No local test infra is needed to exercise this in code: `EmailGrievancePollerTest`
(`src/test/java/com/aigre/email/`) uses an embedded fake mailbox
([GreenMail](https://greenmail-mail-test.github.io/greenmail/)), not a real IMAP server.

---

## Switching providers

Default is Ollama (offline, no API key, no per-call cost). To use Anthropic instead:

1. Set `ANTHROPIC_API_KEY` as a **persistent environment variable** in your own shell
   — never paste an API key into a chat/AI session. On Windows:
   ```
   setx ANTHROPIC_API_KEY "sk-ant-..."
   ```
   Open a **new** terminal after this — `setx` doesn't propagate to already-running
   shells.
2. Edit `src/main/resources/application.yml`:
   ```yaml
   llm:
     provider: anthropic   # was: ollama
   ```
3. Restart the backend.

Flip `llm.provider` back to `ollama` to return to the offline default — no other
config changes needed either direction.

---

## Ports reference

| Service | Port | Configurable via |
|---|---|---|
| Backend (Spring Boot) | 8085 | `server.port` in `application.yml` |
| Postgres (pgvector) | 5434 | the `docker run -p` flag + `spring.datasource.url` |
| Ollama | 11434 (Ollama's default) | `ollama.base-url` in `application.yml` |
| Frontend dev server | 4200 (or next free port) | `ng serve --port <n>` |
| Embed-widget demo page (optional) | 5500 | `npx http-server -p <n>` — any unused port, must differ from AIGRE's own |
| Two-machine deployment (optional) | app 8087, Postgres 5434, embed demo 5500 | host-port mappings in `docker-compose.yml` on Machine B (the app container still listens on 8085) |

---

## Verifying everything is up

```
curl http://localhost:8085/actuator/health          # {"status":"UP"}
curl http://localhost:11434/api/tags                 # 200 = Ollama reachable
docker ps --filter name=aigre-pg                     # container running
curl http://localhost:8085/departments               # REG, DL, FR, TITLE, DEALER (dmv) or the city departments
curl http://localhost:8085/app-config                # "unitLabel":"Bureau" under the dmv profile
```
