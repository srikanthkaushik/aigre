# AIGRE — Onboarding a New Department

How to add a genuinely new city department — not just more documents for an
existing one — including pulling its policy PDFs from a public URL. This is
a reusable, repeatable admin action: onboarding department #2 (and #3, #4...)
needs no code change or redeploy. See `PROJECT.md`'s "Onboard a new
department from a public URL" for the full build history and the two real
bugs found while shipping this; this doc is the operational how-to.

## What this does, in one call

`POST /admin/departments` — given a department code/name and a public URL
that links PDF policy documents — will:

1. Crawl the URL's HTML for `<a href="...">` links whose resolved URL
   contains `.pdf`.
2. Download each one, skipping (not failing on) dead links or responses
   that aren't actually a PDF.
3. Write the surviving PDFs into `test-data/documents/<CODE>/`.
4. Insert the department into the `departments` table.
5. Refresh the classifier's live prompt so it immediately recognizes the
   new department.
6. Re-ingest the **entire** RAG corpus (not just the new documents — see
   [Corpus reset](#corpus-reset-not-incremental) below).

After it returns, the new department is a real classification/routing
target and its documents are searchable via the citizen chat — no restart,
no config edit, no frontend rebuild.

## 1. Get an ADMIN token

The endpoint is `ADMIN`-role-gated (creating a routing target is bigger
blast-radius than day-to-day supervisor work). Log in as one of the seeded
`ADMIN` accounts (see `test-data/sql/seed.sql` — e.g. `ops.admin`, demo
password `Demo1234!` per `RUNNING.md`):

```
curl -s -X POST http://localhost:8085/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"username\":\"ops.admin\",\"password\":\"Demo1234!\"}"
```

Grab the `token` field from the response for the next step.

## 2. Call the onboarding endpoint

```
curl -s -X POST http://localhost:8085/admin/departments ^
  -H "Content-Type: application/json" ^
  -H "Authorization: Bearer <TOKEN>" ^
  -d "{\"id\": \"PRD\", \"name\": \"Parks and Recreation Department\", \"shortName\": \"Parks and Recreation\", \"jurisdictionNotes\": \"public parks maintenance, playground equipment, recreation program registration, sports field scheduling, park tree hazards, dog park facilities.\", \"sourceUrl\": \"https://example.gov/parks-department/policy-documents\"}" ^
  --max-time 600
```

| Field | Required | Notes |
|---|---|---|
| `id` | yes | 2-10 uppercase letters, matched against `^[A-Z]{2,10}$` (lowercase is upper-cased automatically). Must not already exist — a repeat call with the same `id` gets `409 Conflict`, not a silent no-op. |
| `name` | yes | Full display name, e.g. what appears in the frontend's department dropdown (`GET /departments`). |
| `shortName` | yes | The short parenthetical label used in the classifier's prompt (e.g. "Transportation" for DOT) — pick something a few words long, not the full `name`. |
| `jurisdictionNotes` | yes | The actual jurisdiction description the classifier reasons over — **this is the single highest-leverage field for classification quality**. Be as specific as the existing 6 departments' entries are (see `schema.sql`'s seed data for the bar to hit) — a thin one-liner will make the classifier worse at recognizing complaints that belong to this department. |
| `sourceUrl` | yes | A public HTML page with `<a href>` links to PDFs. Relative links are resolved automatically against this URL. |

**Use a generous `--max-time`.** A page linking dozens of PDFs downloads
them one at a time, not in parallel — a large page can genuinely take
several minutes. A client that gives up waiting does **not** stop the
server-side work (see [If your client times out](#if-your-client-times-out-thats-fine-now)
below) — the call still completes, you just won't see the response.

### Response shape

```json
{
  "departmentId": "PRD",
  "pdfsDownloaded": 8,
  "skippedLinks": ["https://example.gov/broken-link.pdf"],
  "ingestionSummary": { "documentsLoaded": 116, "corpusPath": "test-data\\documents" }
}
```

`skippedLinks` lists every PDF link that didn't make it in (404, non-PDF
response, or a write failure) — worth a look if `pdfsDownloaded` is lower
than expected.

## 3. Verify it worked

```
curl -s http://localhost:8085/departments
```

Should now include the new department. Then try a complaint that clearly
matches its jurisdiction:

```
curl -s -X POST http://localhost:8085/grievances ^
  -H "Content-Type: application/json" ^
  -d "{\"rawText\": \"<a complaint matching the new department's jurisdiction>\"}"
```

`departmentPredicted` in the response should be the new code, with a
reasonable `classificationConfidence`. If it isn't, the most common cause is
a `jurisdictionNotes` value that's too thin or too generic — compare it
against the existing 6 departments' entries in `schema.sql`.

## Manual fallback: you already have the PDFs locally

If `sourceUrl` can't be crawled at all (bot-blocked, login-gated, or you
just already have the files) or missed some links the heuristic couldn't
see, you can register the department and ingest its documents without going
through the crawler. **The onboarding endpoint always tries to crawl
`sourceUrl` first — there's no flag to skip that** — so this path bypasses
`POST /admin/departments` entirely rather than fighting it.

### 1. Place the documents

Copy your files into a folder named for the department code — same
convention `CorpusIngestionService` already uses for every department
(folder name = department code, no validation against any list):

```
test-data\documents\DMV\
```

**Not limited to PDFs.** `CorpusIngestionService` parses every file under
this folder via Apache Tika regardless of extension — `.txt` works today
(most of the existing corpus for the original 6 departments is plain
`.txt`, not PDF), and anything else Tika can parse (`.docx`, `.html`, ...)
should too. `.txt` and PDFs can be mixed freely in the same department
folder.

### 2. Insert the department row directly

```
docker exec aigre-pg psql -U aigre -d aigre -c "INSERT INTO departments (id, name, short_name, jurisdiction_notes) VALUES ('DMV', 'Department of Safety - Division of Motor Vehicles', 'Motor Vehicles', 'vehicle registration, drivers licenses, title transfers, road tests, vanity plates, vehicle inspections.');"
```

Put real care into `jurisdiction_notes` — same as the API's `jurisdictionNotes`
field, it's the single highest-leverage piece of text for classification
quality (see the field table above).

### 3. Restart the backend

`DepartmentDirectory` (the classifier's live department cache) only
refreshes when `POST /admin/departments` calls it — since this path
bypasses that endpoint, nothing tells the running classifier a new
department exists until it re-reads the `departments` table, which happens
once at startup:

```
mvn spring-boot:run
```

### 4. Re-ingest the corpus

This is the step that actually embeds your PDFs into the RAG vector store
— nothing before this point has touched pgvector:

```
curl -s -X POST "http://localhost:8085/ingest/reset?confirm=true"
```

### 5. Verify

```
curl -s http://localhost:8085/departments
```

should list the new department, and a complaint matching its jurisdiction
should classify into it (see [step 3 above](#3-verify-it-worked) for the
exact call).

## Known limitations

- **Employee/staff provisioning is manual, not part of this flow.** A
  freshly onboarded department has zero `department_employees` rows — a
  grievance routed there has no one to claim it in the employee dashboard
  until someone adds staff separately (direct SQL today; no admin UI for
  this yet).
- **The crawler only looks for PDFs.** `PdfCrawlService` matches links
  containing `.pdf` — a source page linking `.txt`, `.docx`, or other
  document formats won't pull them in via `POST /admin/departments`, even
  though `CorpusIngestionService` itself would happily parse them (see the
  [manual fallback](#manual-fallback-you-already-have-the-pdfs-locally)
  above). PDF-link detection is also best-effort even for PDFs, not
  exhaustive — it looks for `.pdf` in the resolved URL, so a redirect-y URL
  with no `.pdf` in it (e.g. `/download?doc=42`) is silently missed, not
  downloaded-and-rejected. Check `skippedLinks`, and if a real document went
  missing entirely, use the manual fallback to add it directly.
- **Some public sites block non-browser requests outright** (`403
  Forbidden`, common bot-protection on `.gov` sites in particular). If
  `sourceUrl` itself can't be fetched at all, the call fails fast with a
  `400` naming the real cause (e.g. `Could not fetch sourceUrl '...': 403
  Forbidden from GET ...`) — no department is created, nothing is written.
  There's no way to make a bot-blocking site crawlable from here; use the
  [manual fallback](#manual-fallback-you-already-have-the-pdfs-locally)
  above — find a different, publicly crawlable source, or download the
  files by hand.
- **Citations can include tangentially-related documents alongside the
  right one.** The citizen chat's retrieval reranker (a local LLM scoring
  pass, not a purpose-built relevance model) doesn't always cleanly
  separate "genuinely relevant" from "same general topic area" — this is a
  pre-existing, documented limitation (see `RetrievalService`'s javadoc),
  not specific to newly-onboarded departments. The answer prose itself is
  still grounded in the right document; the citation list can just include
  a few extra items.

### Corpus reset, not incremental

Step 6 above (`CorpusIngestionService.reset()`) wipes and rebuilds the
**entire** vector store, all departments — not just the new one's
documents. This is the same mechanism `POST /ingest/reset` already used;
onboarding a new department doesn't add a lighter-weight incremental path.
At this corpus's scale that's a few seconds' work; if departments and
document counts grow much larger, this becomes a real availability window
for the citizen chatbot during onboarding — worth revisiting before this
scales past a demo-sized corpus.

### If your client times out, that's fine now

An earlier version of this endpoint had a real bug here: a client that gave
up waiting (e.g. a short `curl --max-time`) could leave the server-side
request in a state that silently wiped the RAG corpus down to zero
documents for **every** department, not just the new one. That's fixed —
a cancelled client connection no longer affects the server-side work's
correctness, just your ability to see the response. Still, there's no
reason to fight it: give the call enough time (`--max-time 600` or more for
a large document set) and just wait for the actual response.
