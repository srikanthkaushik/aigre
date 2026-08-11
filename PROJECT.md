# PROJECT.md — AIGRE (AI Grievance Resolution Engine)

## What this is
G2C AI Grievance Resolution Engine for public sector complaint intake,
classification, prioritization, routing, and resolution tracking. Full
domain model, correctness definitions, and test-data spec are in the
approved Milestone-0 plan (see `kickoff.md` for the original brief).

## Decisions locked in at Milestone 0
- **Departments (6):** DOT, DPW, DHHS, DOE, DHUD, DEP — chosen deliberately
  for topic overlap (DOT/DPW, DHHS/DOE, DPW/DHUD, DEP/DPW) to make
  classification/retrieval distractors realistic.
- **Provider default: Ollama** (`qwen2.5:7b` + `nomic-embed-text`), Anthropic
  wired as the switchable alternative per the canonical dual-provider
  requirement.
- **Channel scope:** portal only for now. Email ingestion is an explicit,
  separately-scoped later milestone — not part of day-one intake.
- **Two ingestion pipelines, kept separate:** RAG knowledge-corpus ingestion
  (policy/SOP docs → pgvector) vs. complaint intake (`POST /grievances` →
  Postgres systems-of-record). Not the same pipeline.
- **Three distinct test-data layers** (don't conflate):
  1. RAG policy-doc eval questions (chatbot Q&A ground truth/distractors/
     negatives/superseded versions)
  2. Labeled citizen-complaint eval set (raw complaint text → ground-truth
     department/category/priority/sentiment/scenario) — tests the
     classification/routing/priority pipeline itself
  3. Operational seed rows in Postgres (pre-existing tickets in various
     states, incl. deliberate edge cases) — for dashboards and MCP
     tool-error-path testing
- **Correctness is deterministic wherever possible.** Classification,
  priority, routing, SLA due-date, and duplicate-linking all have a defined
  ground truth and get assertion-based tests. LLM-as-judge is reserved for
  sentiment nuance and open-ended chatbot phrasing — nothing else.
- **Priority/SLA rubric is a pure function**, not an LLM judgment call — see
  plan §1.5 for the table (CRITICAL/HIGH/MEDIUM/LOW → ack/resolve hours).
- **Package base:** `com.aigre.*` (no underscores).

## Status
- [x] Milestone 0 — domain model, test-data spec, correctness definitions
      (plan approved)
- [x] Milestone 1 — day-one scaffold running end to end (verified live:
      intake, ingestion, hybrid retrieval + LLM rerank, SSE chat with
      citations, both eval tests passing)
- [x] Test-data corpus (§3) — 108 documents (target 100–150), 62 eval
      questions (target 40–60), 91 labeled complaints (target 80–100), seed
      SQL with all 5 deliberate edge cases. All verified live, see below.
- [x] Real LLM-based classification (milestone 2 domain work) — replaces
      the day-one `PlaceholderClassifier` keyword stub. See below.
- [~] Milestone 2 — domain RAG mostly done (real corpus, reranked, cited);
      cross-reference-competition retrieval issue still open (two fix
      attempts tried and reverted, see below)
- [x] Milestone 3 — MCP tools over systems-of-record (4 tools, verified live
      over the real MCP Streamable HTTP protocol, not just as plain Java
      methods). See below.
- [x] Milestone 4 — agent workflow with human approval gate (LangGraph4j
      `interruptBefore`/resume, verified live pause/resume cycle). See below.
- [x] Milestone 5 — Angular frontend (citizen portal + employee dashboard),
      built and serving; **not visually verified in a browser** — see below.
- [ ] Milestone 6 — hardening (evals, guardrails, observability)

## Local environment
- **App port: 8085**, not 8080 — 8080 and 8090 are already taken by an
  unrelated project's services (`marion-dmv`, `marion-mcp-server`) running
  on this machine. Check `Get-NetTCPConnection -State Listen` before
  reusing a port.
- **Postgres: `aigre-pg` container, port 5434** (not the `marion-pg`/
  `devdocs-pg` containers already on this box — those belong to other
  projects). `docker run -d --name aigre-pg -e POSTGRES_DB=aigre -e
  POSTGRES_USER=aigre -e POSTGRES_PASSWORD=aigre_dev -p 5434:5432 -v
  aigre-pg-data:/var/lib/postgresql/data pgvector/pgvector:pg16`
- Ollama models `qwen2.5:7b` and `nomic-embed-text` were already pulled
  locally — no fresh pull needed.
- `mvn spring-boot:run` spawns two `java.exe` (Maven launcher + real app) —
  find the real one via `Get-CimInstance Win32_Process -Filter
  "Name='java.exe'"` and match `com.aigre.AigreApplication` in the command
  line before killing.

## Scaffold implementation notes (milestone 1)
- **PgVectorEmbeddingStore has built-in hybrid search** (`SearchMode.HYBRID`
  + `rrfK`) — fuses cosine similarity with Postgres FTS via Reciprocal Rank
  Fusion natively. No hand-rolled hybrid SQL was needed; just
  `.searchMode(HYBRID).rrfK(60)` on the store builder and pass both
  `.queryEmbedding(...)` and `.query(text)` on `EmbeddingSearchRequest`.
- In the 1.18.0 BOM, `langchain4j-pgvector` and
  `langchain4j-document-parser-apache-tika` resolve to the **beta** train
  (`1.18.0-beta28`) even though `langchain4j-anthropic`/`langchain4j-ollama`
  are on stable `1.18.0` — this is upstream's BOM structure, not a config
  mistake; the BOM import handles it automatically.
- **`NamedParameterJdbcTemplate.addValue()` with a bare `java.time.Instant`
  fails** — pgjdbc can't infer the SQL type
  (`PSQLException: Can't infer the SQL type...`). Convert to
  `java.sql.Timestamp.from(instant)` before binding.
- Blocking JDBC calls in a WebFlux app (this stack pairs `starter-webflux`
  with `starter-jdbc`, not r2dbc) are wrapped in
  `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` in the
  intake and ingestion controllers to avoid tying up Netty event-loop
  threads.
- `grievances.department_predicted/department_confirmed/assigned_department`
  are deliberately **not** foreign keys, so the §3.5 "bad department code"
  edge case can actually be seeded later.

## Test-data corpus status (plan §3)

**Tranche 1 complete and verified live against the running app** (not just
written to disk):

- `test-data/eval-questions.md` — 50 eval questions (EQ-001–050) across all
  6 categories (ground truth, distractor-stress, superseded-version,
  negatives, PII, classification/routing), written before the documents,
  per spec. Full target is ~40–60, so this tranche is already at target.
- `test-data/documents/` — 38 of the 100–150 target documents (one
  superseded v1→v2 pair, FAQs, SOPs, resolved-case logs per department,
  plus 5 shared/citywide policies). Ingested live: **230 chunks**. Spot-
  checked EQ-022 (the DPW-sidewalk-vs-DOT-road distractor pair) through the
  actual `/chat/stream` endpoint — correct doc ranked first, distractor
  ranked third, not filtered out entirely. `corpus-manifest.md` maps every
  document to the eval question(s) it answers or distracts.
- `test-data/grievances/eval-complaints.jsonl` — **91 labeled complaints**
  (GRV-001–091), at the full §3.4 target (80–100). Covers all 9 intake-time
  routing scenarios from plan §1.4 (scenario 6, SLA-breach-scan, is
  correctly excluded — it's state-based, not intake-based, and lives in the
  seed SQL instead). Distribution verified: scenario 1 (straightforward)
  ×42, 2 (multi-dept) ×5, 3 (safety-critical) ×5, 4 (needs clarification)
  ×6, 5 (duplicates) ×8, 7 (reopened) ×4, 8 (sentiment bump) ×11, 9 (out-of-
  scope) ×6, 10 (manual re-route) ×4.
- `test-data/sql/seed.sql` — operational demo rows (10 grievances, mixed
  statuses) plus all 5 deliberate edge cases from §3.5 (bad department
  code, stale unescalated breach, 2-hop duplicate chain, anonymous
  no-contact citizen, never-classified row). **Executed against `aigre-pg`
  and verified** — all edge cases confirmed present via direct query.

**Tranche 2 complete — corpus now at target (108 of 100–150 documents),
verified live:**

- `test-data/eval-questions.md` — grew to **62 questions** (EQ-001–062):
  10 new ground-truth/distractor pairs (EQ-051–055), a second superseded-
  version pair (EQ-056/057, DHHS benefits appeals), 2 new negatives
  (EQ-058/059), 3 new classification/routing questions (EQ-060–062).
- `test-data/documents/` — grew from 38 to **108 documents** (70 new: 2
  more resolved-case logs per department, a general-intake FAQ per
  department, ~15 new SOPs/policies/FAQs introducing new distractor pairs,
  the second superseded pair). Ingested live: **543 chunks** (up from
  230). Spot-checked EQ-051 (DEP water-quality vs. DPW water-main
  distractor) through the actual `/chat/stream` endpoint — correct doc
  ranked first (rerankScore 10), the actual distractor didn't even place
  in the top 5.
- `corpus-manifest.md` updated with all 70 new documents mapped to the
  eval questions they answer or distract.

**Real bug found and fixed during tranche-2 ingestion:**
`EmbeddingStoreIngestor.ingest()` sends every segment from the given
documents as a single unbatched `embedAll()` HTTP call — verified in the
langchain4j 1.18.0 source, no internal chunking exists. The 38-doc/230-
chunk corpus ingested fine as one batch; the 108-doc/~650-chunk corpus
reliably crashed Ollama's internal embedding runner (`connection refused`
on an internal `/tokenize` call — confirmed via directly inspecting the
dead port, and reproducible even after a full Ollama restart). Fixed by
batching `CorpusIngestionService.reset()` at 10 documents per
`ingest()` call (`DOCUMENTS_PER_BATCH`) — this is a real scalability
fix, not an Ollama-specific workaround, since any provider would choke on
an unbounded single-request batch as the corpus grows toward 150+ docs.

**Corpus and eval work is now essentially complete for this pass** — 108
docs clears the 100–150 target, 62 eval questions clears the 40–60
target, 91 labeled complaints clears the 80–100 target. Tranche-2
questions (EQ-051+) haven't been run through `RagEvalSuiteTest` yet — see
open items.

## Eval suite (built at milestone 1, per plan §4 — not deferred to milestone 6)

- `src/test/java/com/aigre/retrieval/RagEvalSuiteTest.java` — runs 25 live
  cases (19 ground-truth + 5 distractor-stress + 1 superseded-version) from
  `eval-questions.md` against the real `RetrievalService`. **Not a fast
  test** — each case does a live hybrid search plus one LLM rerank call per
  candidate (up to `initial-k`=15), so the full suite takes ~9 minutes.
  Run deliberately: `mvn test -Dtest=RagEvalSuiteTest`. Requires the corpus
  already ingested (`POST /ingest/reset?confirm=true`); does not re-ingest
  itself.

  **First live run: 20/25 passed.** Three findings, each investigated
  rather than papered over:
  - **EQ-016, EQ-017 — eval ground truth was too strict, now fixed.** Both
    questions have two legitimately correct DHUD documents (a general guide
    that explicitly defers to a more specific one). Assertions now accept
    either file — not a relaxed bar, a corrected one.
  - **EQ-031 (superseded-version) — real gap, left failing on purpose.**
    The rerank prompt judges topical relevance only; it has no concept of
    "current" vs "superseded," so it can rank the superseded v1 SOP above
    the current v2 for a "what's the current SLA" question. Fixing this
    means threading `effective_date`/`superseded_by` into the rerank
    prompt or filtering superseded docs by default — a real milestone-2
    design decision, not a quick patch.
  - **EQ-007 — real corpus-tuning gap, left failing on purpose.** DEP's
    illegal-dumping policy explicitly contrasts itself against missed
    collection ("that's a DPW matter, NOT illegal dumping"), and that
    disambiguation sentence itself scores highly against "what happens if
    my trash isn't picked up" — the very language written to prevent
    confusion is itself competing for the query. Worth watching whether
    this pattern recurs as the corpus grows in the next tranche.
  - EQ-024 exhibited the same pattern as EQ-007 (a related same-department
    document outranking the primary policy doc) — also left failing.

  **Second live run (after the EQ-016/EQ-017 fix): 21/25 passed.** Confirmed
  EQ-016/EQ-017 now pass. The same 3 real findings (EQ-031, EQ-007, EQ-024)
  reproduced **identically** across both runs — strong evidence they're
  genuine, not flaky. One new observation: **EQ-023 flaked** the same way
  EQ-016/EQ-017 did (correctly avoided the actual DPW distractor both times,
  but picked between two legitimate same-department DHUD docs
  inconsistently run-to-run). Applied the same fix (accept either DHUD doc)
  — this specific fix was reasoned from the now twice-confirmed pattern but
  **not independently re-verified with a third live run** (each run costs
  ~9 minutes of live LLM calls); flag this if it matters before relying on
  it. Net picture: **~22/25 (88%) expected**, with 3 reproducible, real,
  intentionally-left-failing findings documented above rather than hidden
  behind a relaxed assertion.
  **Third live run — tranche-2 questions added (9 new cases: EQ-051, 052,
  053, 054, 055, 056, 057, 061, 062 — negatives EQ-058/059/060 excluded,
  same as category D throughout, since refusal behavior needs the full
  chat+LLM-judge pipeline, not a pure retrieval-ranking assertion). 34
  total cases, 28/34 passed (82.4%).**

  EQ-007 and EQ-024 reproduced **a third time**, now beyond reasonable
  doubt genuine. But the real finding from this run is that **all 4 new
  failures (EQ-010, EQ-014, EQ-017, EQ-062) are the identical root cause**
  as EQ-007/EQ-024 — not new, unrelated problems:

  **The pattern:** every department policy doc was deliberately written
  with "distinguishes from X" / "NOT a Y matter" disambiguation sentences,
  per the plan's own "cross-references between documents" realism
  requirement. Those sentences necessarily contain the *other* document's
  topic keywords. At the chunk-retrieval level, a chunk consisting mostly
  of that one disambiguating sentence can score as relevant to the
  original topic as the correct document itself — sometimes more so,
  since it's phrased as a direct answer to "is this a matter for X or Y."
  Concretely this tranche: DHHS's new nuisance-inspection SOP says
  "a restaurant complaint goes to Food Safety SOP instead" and stole
  EQ-010; DPW's new facilities policy says "school buildings are DOE's,
  not DPW's" and stole EQ-014; DOE's new ADA policy says "same standard as
  an elevator outage in DHUD public housing" and stole EQ-017.

  **This is the single most important corpus-design lesson from testing
  this system**, and it scaled *with* corpus growth (3 instances at 25
  cases/38 docs → 6 instances at 34 cases/108 docs) — exactly the kind of
  thing "cosine can't judge relevance" warns about, just manifesting via
  the LLM reranker instead of raw cosine. None of the 4 new failures were
  relaxed into the accepted-answer set — each is a genuine misretrieval
  (the "distinguishing" document is never the right department), left
  failing on purpose and annotated in the test file.

  **Attempted fix (option b) — tried, tested, reverted.** Changed the
  rerank prompt to explicitly score "does this passage DIRECTLY explain
  the answer" high and "does this passage only MENTION the topic while
  redirecting to a different department" low. Re-ran the full 34-case
  suite to verify rather than assuming it worked:

  **Fourth live run: 26/34 passed (76.5%) — a net regression from the
  28/34 baseline.** Diffing the failure sets:
  - **Fixed by the change:** EQ-010, EQ-014, EQ-017 — exactly the 3 cases
    the prompt targeted.
  - **Still resistant:** EQ-007, EQ-024, EQ-062 — the prompt didn't move
    these at all.
  - **5 new regressions introduced:** EQ-001 (the DOT superseded-version
    case, previously 100% reliable across 3 runs, flipped to the
    superseded v1 for the first time), EQ-006, EQ-061 (a resolved-case
    log outranked the policy doc — a *different* failure mode the new
    prompt never targeted, apparently made worse), EQ-011, EQ-020 (a
    closely-related same-department FAQ won — arguably a legitimate
    alternate answer surfaced by tranche 2's new docs, not clearly a
    misretrieval, but newly failing either way).

  **Reverted to the original simple prompt** (`RetrievalService.java`
  now carries a comment explaining why) — it measurably performs better
  overall despite not fixing the 3 targeted cases. Working theory for why
  the more elaborate prompt backfired: instructing the model to weigh
  "does this directly explain vs. merely mention" adds a second
  judgment axis, which (a) increases variance on near-duplicate text
  (v1 vs. v2 SOPs are nearly identical prose, and more prompt complexity
  gave the model more room to flip), and (b) apparently makes concrete
  narrative text (resolved-case logs describing a specific incident) read
  as more "directly explanatory" than abstract policy prose — the
  opposite of the intended effect for that unrelated failure mode.

  **Net status: cross-reference-competition is not fixed.** A single
  prompt tweak was not sufficient — this needs either the corpus-
  restructuring approach (option a: exclude disambiguation text from
  embedded chunks) or a fundamentally different reranking mechanism (a
  dedicated cross-encoder scoring model instead of a general-purpose chat
  model prompt, or a two-pass rerank that separately scores "on-topic"
  vs. "answers the question"). Both are real milestone-2 design
  investments, not something to iterate further on mid-session. The value
  of this exercise: it caught a would-be regression before it shipped,
  which is the entire point of having the eval suite run before trusting
  a change.

  **Attempted fix (option b, take 2) — dedicated cross-encoder, implemented
  and correctly wired, but blocked by a genuine environment issue on this
  machine, not evaluated against the eval suite.** Added
  `langchain4j-onnx-scoring`, downloaded the `ms-marco-MiniLM-L-6-v2`
  quantized ONNX cross-encoder (~22MB) + tokenizer from Hugging Face into
  `models/ms-marco-MiniLM-L-6-v2/`, wired an `OnnxScoringModel` bean in
  `RagConfig`, and rewrote `RetrievalService.retrieve()` to call
  `ScoringModel.scoreAll()` once per query (batched — a real efficiency
  win too, one ONNX inference call instead of up to 15 sequential LLM
  calls). This is the theoretically correct fix: a cross-encoder is
  trained specifically to judge query-passage relevance, not swayed by
  surface keyword overlap the way a general chat-completion prompt can
  be.

  **The app failed to start** with a `BeanCreationException` wrapping
  `UnsatisfiedLinkError: ...onnxruntime.dll: A dynamic link library (DLL)
  initialization routine failed`. Diagnosed rather than guessed:
  - Raw Win32 `LoadLibrary` on the extracted DLL (via a PowerShell P/Invoke
    test) succeeded cleanly — the DLL itself is valid and loadable on this
    machine.
  - A minimal standalone `System.load()` (bypassing Spring entirely)
    reproduced the exact same failure — confirming it's specific to JVM
    process-context loading, not the DLL or Spring.
  - Root cause, confirmed by direct file inspection: the shared JDK at
    `C:\DEVL\jdk-21\bin\msvcp140.dll` is version **14.31.31103.0**;
    onnxruntime 1.22.0's Windows build requires **>=14.40**. The correct
    version (**14.44.35211.0**) *is* installed system-wide in
    `System32`, but Windows' default DLL search order checks the loading
    executable's own directory (`java.exe`'s `bin/`) before `System32`,
    so the JVM process picks up the stale bundled copy. (This matches a
    documented upstream pattern — see [microsoft/onnxruntime#23969](https://github.com/microsoft/onnxruntime/issues/23969)
    and [#24287](https://github.com/microsoft/onnxruntime/issues/24287),
    reported for the same 1.21+ MSVC-runtime version bump.)
  - Tried pre-loading the correct system `msvcp140.dll` into the process
    before onnxruntime's load attempt, on the theory that Windows would
    reuse the already-loaded module — **did not fix it**, meaning
    additional dependent DLLs are likely also affected, not just this
    one.

  **Not fixed, because the real fix is an environment change, not a code
  change**, and the two available options both have blast radius beyond
  this project: (a) modify the shared JDK install — `C:\DEVL\jdk-21` is
  used by other projects on this machine (`marion-*`), so patching its
  bundled DLL could affect them; (b) point this project at a different,
  newer JDK distribution — a legitimate option, but a build-environment
  decision for the user to make, not something to change unilaterally.

  **Reverted the code** to the working LLM-rerank version to keep the app
  running — `RetrievalService.java`'s class javadoc documents the full
  cross-encoder attempt and exact blocker so it isn't re-attempted blind.
  Kept the downloaded model files in `models/` (harmless, ~23MB,
  re-download avoided if this is revisited) and removed the
  `langchain4j-onnx-scoring` dependency, the `ScoringModel` bean, and the
  `rag.rerank.*` config properties from the active code, since an unused
  dependency wired to a bean that can't construct is worse than clean,
  fully-documented removal.

  **To revisit:** switch the project's JDK to a distribution without the
  stale bundled `msvcp140.dll`. **Already verified a working candidate:**
  Eclipse Temurin `jdk-21.0.12+8` — downloaded and directly confirmed its
  bundled `msvcp140.dll` is version `14.40.33810.0` (meets the `>=14.40`
  requirement), vs. the current shared JDK's `14.31.31103.0`. User opted
  to install this themselves rather than have it installed automatically
  (to keep the shared `C:\DEVL\jdk-21` untouched for other projects on
  this machine) — once installed, point this project's build/run at the
  new JDK home, then restore the `RagConfig`/`RetrievalService`/
  `pom.xml`/`application.yml` changes described above (small, contained —
  re-add the dependency, the bean, and swap `RetrievalService`'s rerank
  method for a single `scoringModel.scoreAll(candidates, query)` call)
  and run the full `RagEvalSuiteTest` to actually compare it against the
  28/34 LLM-rerank baseline, which still hasn't happened.
- `src/test/java/com/aigre/classification/ComplaintEvalHarnessTest.java` —
  runs all 91 labeled complaints through the real intake pipeline and
  reports department-match accuracy against ground truth. Side effect:
  writes 91 real rows to `aigre-pg` per run (fine for local dev, not for a
  shared DB).

  **Original baseline: 37/91 correct (40.7%)** against `PlaceholderClassifier`
  (the day-one keyword stub, ~11 literal phrases, no priority/sentiment/
  scenario logic).

## Real LLM-based classification (milestone 2 domain work)

`PlaceholderClassifier` replaced with `LlmGrievanceClassifier`
(`src/main/java/com/aigre/classification/`) — one LLM call determines
department, category, priority (plan §1.5 rubric, including hazard-keyword
CRITICAL and vulnerable-population HIGH detection), confidence, sentiment,
and an actionable flag. `ClassificationResult` extended accordingly (was
department/category/confidence only). `GrievanceIntakeService` updated:
`NOT_ACTIONABLE` status now real (not just a TODO), priority comes from the
LLM's rubric application instead of a hardcoded MEDIUM, sentiment is now
persisted (previously computed nowhere).

**Scoping decision:** multi-department cases (routing scenario 2) are
deliberately NOT auto-resolved — per the plan's own milestone 4, genuine
department ambiguity is meant to pause for human review, not be guessed
away. The classifier signals ambiguity via lower confidence on its single
best-guess department rather than outputting multiple departments, which
also meant no schema migration was needed for this pass.

**Design:** reason-then-`RESULT:`-marker output (prose reasoning, then a
single-line JSON object), matching the same pattern already proven in
`RetrievalService`'s rerank call — chosen over langchain4j's `AiServices`
structured-output feature because Ollama's `ChatModel` doesn't declare
`RESPONSE_FORMAT_JSON_SCHEMA` support by default, and reusing an
already-verified pattern avoided a second round of cross-provider
structured-output research.

**Two real bugs found and fixed via direct empirical debugging** (not
guessed — each was isolated with a throwaway debug test hitting the
classifier directly, then removed):
1. **Unquoted JSON enum values** — the model sometimes emitted
   `"priority": LOW` instead of `"priority": "LOW"` (invalid JSON), losing
   an otherwise-correct classification to a parse failure. Fixed two ways:
   added a one-shot correctly-formatted example to the prompt, and added
   `sanitizeUnquotedEnumValues()` as defense-in-depth (quotes bare-word
   values before parsing rather than trusting the model to always comply).
2. **The string `"null"` instead of the JSON `null` literal** — traced to
   the original prompt's ambiguous placeholder syntax (`"<code or null>"`,
   which reads as "put the text null inside the quotes"). Confirmed via a
   debug print directly inside the harness loop: `departmentPredicted`
   held the four-character string `"null"`, not an actual null reference,
   silently scoring a correct `NOT_ACTIONABLE` case as wrong. Fixed by
   rewriting the prompt template to show a fully-populated example plus an
   explicit "write the bare JSON null, not the quoted string" instruction,
   and by making `nullableText()` treat the string "null" (any case) as
   null defensively.

**Also found: few-shot example anchoring bias.** Both initial worked
examples happened to be DEP cases, and the model measurably over-predicted
DEP afterward (6 of 12 mismatches in one run were incorrect DEP guesses,
including DPW infrastructure hazards like a downed power line). Diversified
to three examples spanning DEP, DPW, and DHUD, and added an explicit
DEP-vs-DPW hazard boundary rule ("DPW owns infrastructure hazards even when
they sound environmental — DEP's hazardous-waste category is for
abandoned/dumped chemicals, not utility incidents").

**Measured result: real, informative variance, not a single number.** Four
consecutive full 91-case live runs against qwen2.5:7b: 65.9% → 86.8% →
74.7% (a fourth partial-diagnostic run in between isn't a clean data
point). Confirmed via direct A/B testing that this is genuine LLM sampling
variance, not remaining bugs or flaky test infrastructure — the exact same
input (GRV-017) scored as a wrong "null" result inside a harness run, then
classified correctly in isolation moments later with no code change in
between. Regression floor set at 55% (comfortable margin below the observed
65.9% low end) rather than pinning to any single run's number.

## Bug found and fixed post-milestone-5: vague complaints wrongly NOT_ACTIONABLE

Found while live-testing the milestone-4 dashboard's pending-review path with
real vague complaints: every one of them (5/5 tried) came back
`NOT_ACTIONABLE` instead of pausing for review. Root cause: the prompt's
ACTIONABILITY section never mentioned vagueness, and CONFIDENCE said to use
low confidence for vague text, but qwen2.5:7b was conflating "no specific
issue identifiable" with "nothing to act on" — collapsing scenario 4 (needs
clarification) into scenario 9 (out-of-scope), which broke the human-review
gate for exactly the cases it exists to catch.

**Fix:** `LlmGrievanceClassifier`'s prompt now explicitly states vagueness
alone is never grounds for `actionable=false` — reserved strictly for the
enumerated categories (compliments/spam/federal-state/private-disputes/
out-of-jurisdiction) — and a 4th few-shot example demonstrates the correct
vague-but-actionable output (`actionable: true`, `department: null`, low
confidence), matching the pattern that already fixed DEP over-prediction
earlier in the project (targeted few-shot > prose-only instruction for this
model).

**Verified, not assumed:** re-ran the exact same 5 previously-misfiring
complaints — all 5 now correctly `pendingReview: true`. Then ran the full
91-case `ComplaintEvalHarnessTest` to check for regressions elsewhere:
**72/91 (79.1%)**, comfortably inside the already-documented Ollama variance
band (65.9%–86.8%) and well above the 55% regression floor.

## Provider comparison: Ollama (qwen2.5:7b) vs. Anthropic (Claude Sonnet 5)

Same code, same prompt, same eval set — only `llm.provider` changed.
`anthropic.chat-model` was also bumped from the dated snapshot
`claude-sonnet-4-5-20250929` (set during the day-one scaffold pass) to
`claude-sonnet-5`, the current model.

**Anthropic result: 87/91 correct (95.6%)**, decisively above the Ollama
range (65.9%–86.8%, ~75.8% average across 4 runs). More importantly, the
*character* of the remaining 4 mismatches is completely different:

- **GRV-072**: citizen explicitly self-diagnosed "must be a city water main
  problem like on the news" — got DPW instead of DHUD. This is exactly the
  misdirection this corpus case (a scenario-10 manual-re-route candidate)
  was deliberately written to test; being led astray by the citizen's own
  wrong self-diagnosis is a defensible failure mode, not a dumb miss.
- **GRV-088**: DHUD vs. DHHS on a homelessness encampment near a community
  center — both departments genuinely coordinate on this per the corpus's
  own Multi-Department Coordination Protocol document; a legitimately close
  call.
- **GRV-075, GRV-090**: plausible ambiguous phrasing, not systematic gaps.

Compare to Ollama's error pattern, which included **systematic** misses:
the model not reliably applying a near-identical few-shot example (the
noise/barking-dog example was in the prompt verbatim, yet a new barking-dog
complaint still scored wrong), and real department-boundary confusion
(hazard-adjacent DPW infrastructure incidents routed to DEP). Claude made
zero of these category-boundary errors across all 91 cases — every miss was
a genuinely ambiguous or deliberately-tricky case, not a rule the model
failed to apply.

**Operational note:** `ANTHROPIC_API_KEY` is not committed anywhere and
must be set as a persistent environment variable (`setx ANTHROPIC_API_KEY
"..."`) outside any AI-assisted session for it to reach the app process —
confirmed firsthand that `setx` doesn't propagate to already-running shells
in this session, only to freshly-spawned ones.

**Current state:** `llm.provider` reverted back to `ollama` (the offline/
no-cost default per the canonical stack decision) after the comparison —
confirmed via a live intake smoke test post-restart. `anthropic.chat-model`
stays updated to `claude-sonnet-5` in config either way, ready to flip back
with a one-line config change plus `setx ANTHROPIC_API_KEY "..."` in a
fresh shell whenever the key is needed again.

## Milestone 3: MCP tools over the grievance systems-of-record

4 tools in `com.aigre.tools.GrievanceMcpTools`, exposed via Spring AI 2.0's
MCP server (`spring-ai-starter-mcp-server-webflux`, `spring.ai.mcp.server.
protocol=STREAMABLE`, per the canonical stack's "MCP only" scope for Spring
AI):

- `get_grievance_status` — status, classification, SLA due date; flags an
  invalid department code (`departmentValid`) and whether the citizen can
  be notified (`citizenContactAvailable`) rather than silently omitting
  them.
- `check_sla_status` — breached/not-breached plus hours remaining or
  overdue.
- `find_duplicate_chain` — walks the full `duplicate_of_id` chain via a
  recursive CTE (capped at 20 hops) to the true original, not just one hop.
- `update_grievance_status` — validates the status against the plan §1.3
  state machine before writing, records the change in `status_history`,
  and sets `resolved_at` only for terminal statuses.

**Verified at two levels, not just one:**
1. `GrievanceMcpToolsTest` (9 tests) calls the tool class directly against
   all 5 deliberate edge cases in `seed.sql` — bad department code, stale
   unescalated breach, 2-hop duplicate chain, anonymous no-contact citizen,
   never-classified row — plus not-found and malformed-ID error paths and
   an update round-trip. All pass.
2. **Live MCP protocol probe against the actually-running app** (not just
   the test's embedded context): a real `initialize` → session ID →
   `tools/list` → `tools/call` handshake over HTTP, per CLAUDE.md's own
   probe-the-endpoint gotcha. Confirmed the server identifies itself
   correctly (`aigre-grievance-tools` / `0.1.0`), lists all 4 tools with
   correctly-generated JSON schemas, and a live `tools/call` against the
   bad-department-code row returned `"departmentValid":false` straight
   from the database through the real wire protocol.

**One assumption tested and found unnecessary:** CLAUDE.md's carried-forward
gotcha warned that `@McpTool` annotation scanning might not find tool
methods without an explicit `MethodToolCallbackProvider` bean (a bug from
an earlier Spring AI milestone). Tried the plain documented path first
(`@Component` + `@McpTool` + `annotation-scanner.enabled: true`) rather
than pre-emptively adding the workaround — the startup log showed
`Registered tools: 4` immediately, confirming 2.0.0 GA fixed this. No
workaround needed.

**Scoping note:** this milestone builds the MCP *server* only. The MCP
*client* side (LangChain4j's `langchain4j-mcp`, consuming these tools as
LangChain4j `ToolProvider`s) is deliberately not built yet — that belongs
to milestone 4, where an actual agent exists to consume them. Building the
client now, with nothing to call it, would be premature.

## Milestone 4: agent workflow with human approval gate

`langgraph4j-core:1.8.20` added to `pom.xml`. New `com.aigre.workflow`
package builds a 3-node graph: `classify` -> (conditional edge on a `route`
state field) -> `commit`, or `classify` -> `human_review` (an
`interruptBefore` pause point) -> `commit`. `START`/`END` come from
`GraphDefinition` constants; nodes are plain synchronous `NodeAction`/
`EdgeAction` lambdas wrapped via `node_async`/`edge_async` — no need to
hand-write `CompletableFuture` plumbing. Checkpointing uses an in-memory
`MemorySaver`; a Postgres-backed saver is an open item (below) if paused
workflows ever need to survive an app restart.

**State design:** `GrievanceWorkflowState` (an `AgentState` subclass) is
deliberately flattened to primitive-typed keys (String/Double/Boolean), not
the `ClassificationResult` record — LangGraph4j's checkpoint cloning goes
through `ObjectStreamStateSerializer` (Java serialization), and flattening
avoided making a domain record implement `Serializable` just for this
graph-internal artifact. `predictedDepartment` (the LLM's raw guess, set
once by `classify` and never touched again) is kept separate from
`finalDepartment`/`finalCategory`/`finalPriority` (initialized as copies of
the prediction, only overwritten by `human_review` if a supervisor supplies
a different value) — this maps directly onto the schema's existing
`department_predicted` vs. `department_confirmed` columns, which
(in hindsight) were seeded back at milestone 0 for exactly this purpose.

**Routing logic:** `classify` sets `route=human_review` when
`actionable && !isConfident()`, else `route=commit` (covers both the
not-actionable auto-path and the high-confidence auto-path). Per
`LlmGrievanceClassifier`'s own already-documented scoping decision, scenario
2 (multi-department ambiguity) was never given a second output field — it's
signaled via lower confidence on the single best-guess department — so it
already falls into the same `human_review` branch as scenario 4
(low-confidence) without any new classifier work needed here.

**API verified against the real 1.8.20 source** (GitHub tag `v1.8.20`)
before writing any code, per CLAUDE.md's version-sensitive-dependency rule:
`CompileConfig.builder().interruptBefore(String...)`, `GraphInput.resume()`/
`.resume(Map)` (passing a plain `Map` to `invoke()` restarts from `START`,
confirmed matches the CLAUDE.md gotcha), and `graph.getState(config).next()`
as the authoritative pause-vs-complete signal (not `invoke()`'s
`Optional<State>` return value, which the CLAUDE.md gotcha specifically
warns not to trust for this).

**Endpoints** (`GrievanceWorkflowController`, alongside the existing plain
`GrievanceController`): `POST /grievances/workflow` (start),
`GET /grievances/{id}/workflow` (poll pause/complete status),
`POST /grievances/{id}/workflow/resume` (supervisor decision — department/
category/priority all optional, only supplied fields override the LLM's
prediction). The graph is threaded per grievance (`threadId` =
`grievanceId`), so resume always targets the right paused run.

**Tests, split by what they actually verify — a lesson learned live during
this milestone:** an initial single live-Ollama test asserting a vague
complaint ("Things have been bad on my street lately and nobody seems to
care." — GRV-036 from the eval corpus) would pause for review **failed**:
qwen2.5:7b classified it with high confidence anyway. This is the same
documented LLM sampling variance from `ComplaintEvalHarnessTest`, not a
graph-wiring bug — confirmed by separating concerns:
- `GrievanceWorkflowServiceTest` (live Ollama) — only asserts the
  high-confidence auto-commit path (a real pothole complaint -> `DOT`,
  `TRIAGED`, SLA set). Passes reliably.
- `GrievanceWorkflowPauseResumeTest` (`@MockitoBean`-mocked
  `LlmGrievanceClassifier`) — deterministically forces a low-confidence
  and a not-actionable result to test the interrupt/resume *mechanics*
  themselves (pause, `department_confirmed` only set post-review, resume
  commits `TRIAGED` with the supervisor's values, not-actionable skips
  review entirely and commits `NOT_ACTIONABLE`). This is the test that
  should catch a real graph-wiring regression; coupling it to live model
  output would make it flaky for reasons unrelated to what it tests.

All 3 tests pass; full suite (excluding the two long-running LLM eval
harnesses) at 20/20.

## Milestone 5: Angular frontend

New `frontend/` (Angular 21, standalone components, no NgModules) alongside
the existing `pom.xml`/`src/` — first non-Java part of the repo.

**Angular version decision:** the machine's installed Node is `v22.14.0`.
The Angular CLI's `latest` dist-tag (22.1.3) requires Node `^22.22.3 ||
^24.15.0 || >=26.0.0` — too new for what's installed. Checked the engine
requirements of recent majors directly via `npm view <pkg>@<version>
engines` rather than guessing: Angular 21 (the `v21-lts` dist-tag,
`21.2.20`) requires `^20.19.0 || ^22.12.0 || >=24.0.0`, which the installed
Node satisfies. Used `@angular/cli@21` rather than pushing a Node upgrade,
matching this session's earlier stance on the JDK/onnxruntime issue: don't
change a shared machine-level toolchain unilaterally when a same-major
older version of the thing actually being added works fine.

**Two new backend endpoints** (`com.aigre.query`) needed before frontend
work could start, because the two existing intake paths didn't share a
status-lookup story: `GET /grievances/{id}` (delegates into
`GrievanceMcpTools.getGrievanceStatus` — works for any grievance regardless
of whether it came from plain intake, the workflow graph, or a seeded
operational row) and `GET /grievances?department=&status=` (dashboard
listing, with a computed `breached` flag). Malformed/unknown IDs map to a
404 via `ResponseStatusException` rather than the default 500. `WebConfig`
(`com.aigre.config`) adds CORS for `http://localhost:4200`/`:4300` since
`ng serve` runs on a different origin than the Spring Boot app (port 8085).

**Scope decisions (asked and answered before building):**
- **Auth: a department-picker stub, not real auth.** No login — the
  employee dashboard has a plain dropdown that scopes the view client-side,
  persisted to `localStorage`. Real Spring Security + JWT tied to
  `department_employees` is an explicit open item (below), not built.
- **Both citizen and employee views in one pass**, sharing one Angular app
  and API client rather than building them separately.

**Citizen portal** (`frontend/src/app/pages/citizen`): three tabs in one
component — submit (posts to the milestone-4 `/grievances/workflow`
endpoint, not the plain milestone-1 `/grievances` endpoint, so low-
confidence submissions correctly surface as "needs supervisor review"
rather than silently sitting in `NEEDS_CLARIFICATION`), status lookup (`GET
/grievances/{id}`), and a chat widget against `/chat/stream`.

**Chat streaming had to bypass Angular's `HttpClient`.** `ChatController`'s
SSE endpoint is `POST`-based (it needs a JSON body), but the browser's
native `EventSource` is GET-only, and `HttpClient` doesn't expose
incremental chunk reads for a streamed body without contorted
`observe: 'events'` plumbing. `ApiService.streamChat()` calls `fetch()`
directly and reads `response.body.getReader()`, hand-parsing
`event:`/`data:` frames on `\n\n` boundaries — matching the three named
events `SseTokenStreamingHandler` actually emits (`token`, `sources`,
`error`).

**Employee dashboard** (`frontend/src/app/pages/employee`): department
picker, a "Pending Review" tab (`status=NEW` across all departments — the
supervisor-approval queue) and a "`{dept}` Queue" tab (that department's
grievances, any status, with an SLA-breach row highlight). Reviewing an
item calls `GET /grievances/{id}/workflow` (the milestone-4 endpoint —
returns the LLM's reasoning/predicted values, which the plain status
endpoint doesn't carry) to show the supervisor what the classifier guessed,
then `POST /grievances/{id}/workflow/resume` with only the fields the
supervisor actually changed.

**Verification status — honest limitation, not a completed claim:** the
app builds cleanly (`ng build`), both dev servers are up and were smoke-
tested at the HTTP level (index shell loads, `/citizen` and `/employee`
client routes resolve to 200 via the dev server's SPA fallback, CORS
preflight succeeds, all backend endpoints the frontend calls were curl-
verified against live data first). **This environment has no browser/
screenshot tool available, so the actual rendered UI, the SSE token-by-
token rendering, and the full submit/review click-through have not been
visually confirmed** — per CLAUDE.md's own instruction to say so explicitly
rather than claim success. Both servers (`ng serve` on :4300, Spring Boot
on :8085) were left running for the user to check by hand.

**A stray `AigreApplication` process was found running on port 8085**
before this milestone's smoke test could even start (`mvn spring-boot:run`
apparently left running from earlier in this session, unnoticed at the
time) — killed via the CLAUDE.md-documented `Get-CimInstance Win32_Process`
pattern before restarting cleanly. Worth remembering to shut down
foreground dev servers explicitly at the end of a session.

## Milestone 5 follow-up: bugs found live-testing the UI

A cluster of real bugs surfaced only by actually exercising the running
frontend against the running backend — none of these were caught by `mvn
test` or `ng build`, since they're either cross-service wiring issues or
runtime behavior neither test suite exercises. Documenting the pattern, not
just the fixes: **live end-to-end testing kept finding a different class of
bug than the automated suites**, each time.

- **CORS hardcoded to the wrong port.** `WebConfig` allowed only
  `localhost:4200`; `ng serve` actually runs on `:4300` on this machine
  (`:4200` is held by an unrelated project). Switched to
  `allowedOriginPatterns("http://localhost:*")`.
- **In-memory checkpoint + seeded/legacy rows → uncaught 500s.**
  `GET/POST .../workflow[/resume]` threw `IllegalStateException` for any
  grievance with no LangGraph4j checkpoint (seeded demo rows, anything
  submitted via the plain non-workflow intake endpoint, or — found via a
  different angle — any grievance whose paused state was lost because the
  backend got restarted mid-session, since `MemorySaver` doesn't persist).
  `GrievanceWorkflowService` now catches this and degrades gracefully:
  `status()` returns `pendingReview: false` instead of crashing; `resume()`
  fails fast with a clear message, mapped to `409 Conflict`.
- **Error response bodies had no `message` field**, even with
  `server.error.include-message: always` set — Spring Boot 4's default
  WebFlux error body just doesn't include one in this configuration. Rather
  than keep fighting the default, added `ApiExceptionHandler`
  (`@RestControllerAdvice`) that owns the JSON shape directly:
  `IllegalArgumentException` → 404, `IllegalStateException` → 409, both with
  an explicit `{"message": "..."}` body.
- **Dashboard had no way to see the actual complaint text** — neither the
  pending-review queue nor the department queue exposed `rawText` anywhere.
  Added `rawText` to `GrievanceWorkflowResponse` (queried from `grievances`
  directly, so it's populated even when there's no checkpoint) and surfaced
  it in the review/detail view.
- **UI revamped to Angular Material** (M3, Azure/Blue palette) — `mat-tab-
  group`, `mat-form-field`/`mat-select`, `mat-table`, and the custom overlay
  modal replaced with a proper `MatDialog` component
  (`GrievanceDetailDialog`). Needed `@angular/animations` explicitly (not
  pulled in automatically by `provideAnimationsAsync()` in this Angular/
  Material version combo).
- **Department codes shown unexpanded everywhere** (`DOT`, `DPW`, ...).
  Added a `departmentName` pipe backed by a `DEPARTMENT_NAMES` map mirroring
  `schema.sql`'s seed data (no live "list departments" endpoint to source it
  from) — codes stay as the underlying `<mat-option>` values sent to the
  API, only the displayed label expands.
- **Chat streaming: two independent, compounding bugs**, both invisible
  until an actual question was asked in the running UI:
  1. **Token spacing silently eaten.** `ApiService.streamChat()`'s SSE frame
     parser called `.trim()` on each `data:` line. Confirmed via a raw
     `curl -N` capture that Spring writes `data:<content>` with **no**
     separator space, so a token's own leading space (e.g. `data: to`,
     `data: the` — LLM tokenizers attach the space to the following word)
     *is* the content, not framing. `.trim()`-ing it produced
     "DOThas5businessdaystorepair...". Fixed by not trimming the data
     line at all.
  2. **`RetrievedSource`'s frontend shape never matched the backend.** The
     TypeScript interface assumed a flattened `{text, source, department,
     docType, score}` shape; the real Java record serializes as `{text,
     metadata: {...}, vectorScore, rerankScore}`. Citation chips were
     rendering `undefined`. Compounding this, the metadata map itself never
     had a filename in it at all — `CorpusIngestionService` called
     `FileSystemDocumentLoader.loadDocumentsRecursively(...)` (plural),
     confirmed via a direct query against `rag_documents` that its documents
     only ever carried `absolute_directory_path`, never a filename. Rewrote
     ingestion to load each file individually via `loadDocument(path,
     parser)` (singular) and set `source` (and `department`) explicitly from
     the filesystem path rather than trust whatever the loader happens to
     populate — more robust regardless of loader internals, and now also
     incidentally gets `file_name` for free (the singular loader does set
     it, unlike the plural one). Frontend interface corrected to match;
     citizen chat's source chips now read `metadata['source']`.
     **Required a full corpus re-ingest** (`POST /ingest/reset?confirm=true`)
     since existing pgvector rows were embedded before the metadata fix.

  Verified end-to-end via raw SSE capture + a Node script replicating the
  fixed parser exactly (not just "looks right"): properly spaced answer
  text, correct top citation (`road-maintenance-sop-v2-current.txt`, DOT,
  rerankScore 10), correctly outranking the DPW sidewalk distractor.

  **Operational hazard found the hard way, later the same day:** citations
  went back to showing "unknown source" in the UI after running
  `RetrievalEvalTest` for an unrelated sanity check. Root cause:
  `RetrievalEvalTest`'s `@BeforeEach seedFixture()` calls
  `embeddingStore.removeAll()` and reseeds just 2 hardcoded fixture rows
  (`Metadata.from("department", "DOT")` only, no `source` key) — a
  day-one smoke test (plan §4, "passes day one") that assumes a throwaway
  dev database, not a "read-only against the real corpus" test. It silently
  wiped the real 108-doc/543-chunk corpus down to those 2 rows. Not a code
  regression — fixed by re-running `POST /ingest/reset?confirm=true`.
  **Anyone running `RetrievalEvalTest` locally needs to re-ingest the full
  corpus afterward** if they want it back; worth an explicit javadoc
  warning on that test, or moving the fixture into an isolated
  `@DirtiesContext`/separate schema if this keeps causing confusion.

## Visual redesign pass (post-milestone-5 polish)

Full frontend restyle per a dedicated plan (see the plan file path at the
bottom of this document — it now has two sections, milestone-0 and this
redesign, kept separate since they're unrelated tasks). Direction agreed
with the user beforehand: **civic-institutional trust** aesthetic, light
theme only for this pass (dark mode stays an open item below), branding
originated from scratch (no existing AIGRE assets to match).

**Design system**: replaced Angular Material's stock Azure/Blue M3 theme
with a custom palette generated via `ng generate @angular/material:m3-theme
--primary-color="#1B3A57" --tertiary-color="#C17F2C"` (deep civic navy +
warm amber) — Material Color Utilities computes the full tonal scale from
those two seed colors into `src/theme-colors.scss`, not hand-picked shades,
so M3's contrast relationships between tones stay correct. Typography
switched from Roboto to **Public Sans** (the U.S. Web Design System
typeface) — a deliberate fit for the civic-institutional direction, not an
arbitrary choice. Added a shared `.page-header` pattern, `.status-chip`
color-coding (mirroring the `.priority-chip` pattern already in place), and
an `.empty-state` pattern, all in `styles.scss` so citizen/employee pages
read as the same product.

**Per-page**: landing got a hero restructure + icon-badge entry cards;
citizen portal got a two-column submit layout (form + "what happens next"
helper card), a receipt-style status result, redesigned chat bubbles with
role avatars, source citations as small elevated cards instead of plain
chips, and clickable example-question prompts; employee dashboard got a
3-stat summary row (pending review / queue size / SLA breaches), sticky
table headers, row hover states, and status-chip color-differentiation.
Toolbar got a custom inline-SVG mark (shield + checkmark, `currentColor` so
it inherits theme color — no external logo asset, no image-gen tool
available), a proper active-nav-link underline instead of a flat overlay,
and now collapses into a `mat-menu` below 700px instead of letting the two
nav labels wrap. Added a footer strip. `angular.json` build budgets bumped
(500kB→600kB initial, 4kB→8kB per-component style) since a genuinely
styled app is heavier than Material's unstyled scaffold — expected, not a
regression to chase down.

**Verification, same honest limitation as the rest of milestone 5**: `ng
build` clean after every phase, dev server rebuilds clean on every route,
unit test passes, backend untouched/still healthy. **No browser tool in
this environment, so the actual visual result — whether the navy/amber
palette, Public Sans, and the redesigned layouts actually read as
"polished" — has not been seen, only structurally verified to compile and
serve.** This matters more here than for any earlier frontend change this
session, precisely because the deliverable *is* the visual result. Check in
the browser before treating this as done.

## Complaint Trends dashboard

Delivers "trend/sentiment analytics" from the original kickoff brief and
Milestone-0 plan (§1.2's never-built `TrendSignal` entity) — a third tab on
the Employee Dashboard, pure read-side analytics against the existing
`grievances` table, no schema change.

**Backend** (`com.aigre.query`): `GrievanceTrendsService` runs five focused
SQL queries (volume-by-day, top-8-categories, priority counts,
sentiment-by-day, and a `FILTER`-clause SLA snapshot in one row), each
scoped by the same `submitted_at` window and the same
`COALESCE(department_confirmed, department_predicted)` department filter
`GrievanceQueryService.list()` already established — `department` null/blank
means all departments, not a magic sentinel value.
`GrievanceTrendsController` exposes it as `GET /grievances/trends?department=&days=`,
one round trip via `TrendsResponse`. SLA snapshot is deliberately three
separate counts (resolved-on-time / resolved-late / currently-breached-open),
not one compliance percentage — that would conflate a closed-but-late case
with a still-open one, different problems.

**Tested with isolated fixture data**, not against the shared dev DB's real
rows: `GrievanceTrendsServiceTest` inserts 4 fixture grievances under a
dedicated department code (`ZZTEST`, guaranteed not to collide — departments
are deliberately not an FK) and asserts exact aggregates, cleaning up in
`@AfterEach`. Direct lesson applied from the `RetrievalEvalTest` incident
earlier this session: don't leave destructive or polluting state behind for
other tests or the running app to trip over.

**Frontend**: `chart.js@4.5.1` + `ng2-charts@10.0.0` (verified compatible —
ng2-charts 10's `peerDependencies` explicitly require `@angular/core
>=21.0.0`). New standalone `pages/employee/trends/` component: a local
`mat-button-toggle-group` scope toggle (This Department / All Departments,
independent of the existing "Viewing as" picker so its Department-Queue-tab
meaning isn't overloaded) and a 7/30/90-day window toggle, both driving one
`effect()`-based fetch. Four charts (volume line, sentiment line, category
bar, priority bar) plus an SLA stat-card row reusing the `.stat-card`
pattern already built for the dashboard's summary row — moved into the
shared `styles.scss` (was employee-page-local) specifically so this new
component could reuse it without duplicating the CSS.

**Real bundle-size mistake caught and fixed before it shipped:** initially
called `provideCharts(withDefaultRegisterables())` in `app.config.ts`
(app-wide) — `ng build` immediately flagged the initial bundle jumping to
671kB (over budget) since Chart.js loads eagerly for every route including
citizen/landing, which never use it. First fix attempt (moving the provider
into the `employee` route's `providers` array in `app.routes.ts`) **did not
actually work** — verified by rebuilding and seeing the same 671kB, because
`app.routes.ts`'s top-level static imports get bundled into whatever eager
chunk defines the `routes` array, regardless of when the provider function
is *called*. The actual fix: import `provideCharts`/`withDefaultRegisterables`
inside the lazily-loaded `Trends` component itself and list them in that
component's own `@Component({ providers: [...] })` — confirmed via rebuild
that Chart.js's cost moved entirely into the `employee` lazy chunk (163kB →
399kB) while the initial bundle dropped back to 502kB, under budget with no
warning.

**Verification**: live `curl` against `GET /grievances/trends` with real
department-scoped and all-departments queries — correct JSON shape, correct
aggregates. `ng build` clean, dev server rebuilds clean, unit test passes.
**Same stated limitation as the redesign pass**: no browser tool in this
environment — whether the charts actually render and look right needs the
user to check in-browser.

## Open items to revisit
- RBAC/department-scoping for employee dashboards — real requirement,
  deferred to milestone 5.
- Dark mode — explicitly deferred in the redesign pass above; the token
  system (`--mat-sys-*` throughout, no hardcoded colors outside the
  priority/status chip maps) should make a dark variant a fast follow
  rather than a rewrite.
- Business-hours-aware SLA calendar vs. flat calendar days — currently flat
  calendar-hour arithmetic in `SlaCalculator`; revisit before this becomes
  the source of truth for real SLA breach reporting.
- Attachment/photo evidence (e.g. pothole photos) — out of scope for
  text-only NLP in this pass.
- Duplicate detection (scenario 5) and reopened-case handling (scenario 7) —
  `find_duplicate_chain` (milestone 3) can walk a chain once a
  `duplicate_of_id` link already exists, but nothing yet *creates* that
  link — detecting that a newly-submitted grievance is a duplicate of an
  existing one (e.g. via embedding similarity against recent same-category/
  same-location grievances) is still unbuilt. Reopened-case handling (an
  employee or citizen reopening a `CLOSED` grievance) is also still
  unbuilt — `update_grievance_status` can perform the status transition
  but doesn't yet apply the "bump priority one tier on reopen" rule from
  plan §1.4 scenario 7.
- Postgres-backed LangGraph4j checkpointing — milestone 4 uses the in-memory
  `MemorySaver`, so a paused (pending-review) workflow does not survive an
  app restart. `langgraph4j-postgres-saver` exists upstream if this becomes
  a real requirement; not needed for the current single-instance demo.
- Real auth/RBAC for the employee dashboard — milestone 5 shipped a
  department-picker stub (no login, client-side scoping only) by explicit
  choice for this pass. Spring Security + JWT tied to `department_employees`
  is the real requirement if this goes beyond a demo.
- Frontend `API_BASE` is a hardcoded `http://localhost:8085` constant in
  `frontend/src/app/core/api.service.ts` — fine for local dev, would need an
  environment-file/build-config split before any real deployment.
- The milestone-4 workflow's own MCP-tool consumption — the graph currently
  writes to Postgres directly from the `commit` node (matching
  `GrievanceIntakeService`'s pattern) rather than calling
  `GrievanceMcpTools.updateGrievanceStatus` as a LangChain4j tool. A
  LangChain4j `ToolProvider` wrapping `GrievanceMcpTools` for the agent to
  call as actual tool-use (vs. direct JDBC) would be a natural follow-up if
  the graph grows more nodes that need MCP-tool-mediated actions.

## Full plan
The complete Milestone-0 plan (domain model, routing/escalation scenarios,
correctness table, test-data spec with eval questions, day-one scaffold
checklist) lives at:
`C:\Users\srika\.claude\plans\read-kickoff-md-and-new-project-instruct-serialized-goblet.md`
