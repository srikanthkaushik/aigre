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

Backend CORS (`com.aigre.config.WebConfig`) is configured for `http://localhost:*`, so
any local port works without a config change. Open whichever port you served on in a
browser.

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
