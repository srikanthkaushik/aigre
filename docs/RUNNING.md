# AIGRE — Running the Application Locally

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
ollama pull nomic-embed-text
```

Ollama should be listening at `http://localhost:11434` (its default) — this matches
`application.yml`'s `ollama.base-url`.

## 3. Run the backend

```
cd C:\DEVL\AIGRE
mvn spring-boot:run
```

Starts on **port 8085**. Confirm with:

```
curl http://localhost:8085/actuator/health
```

**Windows note**: `mvn spring-boot:run` spawns two `java.exe` processes (the Maven
launcher and the actual app). If you need to kill and restart it, find the real one
first:

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'com.aigre.AigreApplication' }
```

## 4. Seed data

Two independent seeding steps — do both for a fully populated dashboard/chatbot:

**a) Operational demo rows** (grievances in various states, plus 5 deliberate edge
cases for testing) — run once against the running Postgres container:

```
docker exec -i aigre-pg psql -U aigre -d aigre < test-data/sql/seed.sql
```

**b) The RAG policy corpus** — loads `test-data/documents/**` (108 documents) into
pgvector. This is a live embedding call per chunk (~543 chunks), so it takes a couple
of minutes and requires Ollama to be running:

```
curl -X POST "http://localhost:8085/ingest/reset?confirm=true"
```

This **wipes and reloads** the entire `rag_documents` table — safe to re-run any time
the corpus changes, but don't run it against a database you don't want reset.

> **Careful**: `RetrievalEvalTest` (see `TEST_SCENARIOS.md`) has a destructive
> `@BeforeEach` that wipes `rag_documents` down to 2 hardcoded fixture rows. If you run
> that test, re-run step 4b afterward to restore the full corpus.

**c) Employee login credentials** — step 4a's seed also creates 12 employee accounts
(2 per department, 1 AGENT + 1 SUPERVISOR), all sharing the same demo password. A real
deployment would never share one password across accounts; fine here since the point is
exercising real Spring Security + JWT auth, not credential hygiene.

| Username | Name | Department | Role |
|---|---|---|---|
| `priya.nakamura` / `marcus.webb` | Priya Nakamura / Marcus Webb | DOT | AGENT / SUPERVISOR |
| `lena.ortiz` / `grant.okafor` | Lena Ortiz / Grant Okafor | DPW | AGENT / SUPERVISOR |
| `a.sandoval` / `r.whitfield` | A. Sandoval / R. Whitfield | DHHS | AGENT / SUPERVISOR |
| `kayla.simmons` / `dennis.choi` | Kayla Simmons / Dennis Choi | DOE | AGENT / SUPERVISOR |
| `priscilla.adeyemi` / `tom.reilly` | Priscilla Adeyemi / Tom Reilly | DHUD | AGENT / SUPERVISOR |
| `nora.fitzgerald` / `sam.alvarez` | Nora Fitzgerald / Sam Alvarez | DEP | AGENT / SUPERVISOR |

Password for all: `Demo1234!`

AGENT can view their department's queue read-only; SUPERVISOR can additionally resume
a paused review and mark grievances resolved/closed. Dashboard access is scoped
strictly to the logged-in employee's own department — there's no picker to switch
departments anymore (see `ARCHITECTURE.md`).

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

Backend CORS (`com.aigre.auth.SecurityConfig`) is configured for `http://localhost:*`,
so any local port works without a config change. Open whichever port you served on in
a browser.

---

## Email ingestion (optional)

A second inbound channel alongside the portal: `com.aigre.email.EmailGrievancePoller`
polls a monitored IMAP mailbox on a schedule and feeds each unread message through the
exact same `GrievanceWorkflowService.start(...)` entry point the portal uses -- same
classification, duplicate detection, human-review pause, and SLA computation. Off by
default (`email.enabled: false`) -- the app runs fine with nothing configured here.

To try it against a real mailbox (e.g. a Gmail account with an
[app password](https://myaccount.google.com/apppasswords), since Google no longer
accepts your regular password for IMAP), set in `application.yml` or via environment
overrides:

```yaml
email:
  enabled: true
  imap:
    host: imap.gmail.com
    port: 993
    protocol: imaps
    username: your-address@gmail.com
    password: ${EMAIL_APP_PASSWORD}
  poll-interval-ms: 60000
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

---

## Verifying everything is up

```
curl http://localhost:8085/actuator/health          # {"status":"UP"}
curl http://localhost:11434/api/tags                 # 200 = Ollama reachable
docker ps --filter name=aigre-pg                     # container running
curl "http://localhost:8085/grievances?department=DOT"  # [] or real rows once seeded
```
