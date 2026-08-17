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
- **Channel scope:** portal was day-one; email ingestion (IMAP polling) was
  the deferred second channel and has since been **built, see below**.
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
- [x] Milestone 2 — domain RAG done (real corpus, reranked, cited).
      cross-reference-competition retrieval issue fixed via corpus
      restructuring after two earlier attempts (elaborate rerank prompt,
      ONNX cross-encoder) were tried and reverted — see below.
- [x] Milestone 3 — MCP tools over systems-of-record (4 tools, verified live
      over the real MCP Streamable HTTP protocol, not just as plain Java
      methods). See below.
- [x] Milestone 4 — agent workflow with human approval gate (LangGraph4j
      `interruptBefore`/resume, verified live pause/resume cycle). See below.
- [x] Milestone 5 — Angular frontend (citizen portal + employee dashboard),
      built and serving; **not visually verified in a browser** — see below.
- [~] Milestone 6 — hardening: PII redaction guardrail done, observability
      extended (see below); eval suite expansion beyond the current 108
      docs/62 questions/91 complaints not pursued further this pass

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

  **Net status at the time: cross-reference-competition not fixed.** A
  single prompt tweak was not sufficient — this needs either the corpus-
  restructuring approach (option a: exclude disambiguation text from
  embedded chunks) or a fundamentally different reranking mechanism (a
  dedicated cross-encoder scoring model instead of a general-purpose chat
  model prompt, or a two-pass rerank that separately scores "on-topic"
  vs. "answers the question"). Both are real milestone-2 design
  investments, not something to iterate further on mid-session. The value
  of this exercise: it caught a would-be regression before it shipped,
  which is the entire point of having the eval suite run before trusting
  a change. **Option (a) was picked up and implemented in a later session
  — see "Cross-reference-competition: fixed via corpus restructuring"
  below.**

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
404 via `ResponseStatusException` rather than the default 500. CORS for
`http://localhost:4200`/`:4300` was added here (`WebConfig`,
`com.aigre.config`) since `ng serve` runs on a different origin than the
Spring Boot app (port 8085) — **superseded later** when real auth landed
and CORS had to move into `SecurityConfig` instead (Spring Security's
filter chain runs in front of WebFlux's own CORS handling; see below).

**Scope decisions (asked and answered before building):**
- **Auth: a department-picker stub, not real auth, for this pass.** No
  login — the employee dashboard has a plain dropdown that scopes the view
  client-side, persisted to `localStorage`. **Replaced later with real
  Spring Security + JWT — see below.**
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

## Citizen-driven clarification (closes an open thread from earlier this session)

Milestone-0's plan named this behavior for scenario 4 ("portal/chatbot prompts
citizen for more detail instead of guessing") but it was never actually built —
low confidence only ever routed to a *supervisor*, never back to the citizen. Built
now: `GrievanceWorkflowService.clarify()` + `POST /grievances/{id}/workflow/clarify`.

**Design**: when a submission pauses (`pendingReview: true`), the citizen gets one
inline "Can you tell us more?" prompt right in the result banner — no new page. If
they add detail, the *combined* text (original + addition) is reclassified; if now
confident, the service auto-resumes the already-paused LangGraph4j workflow itself,
reusing the exact same `GraphInput.resume()` mechanism the supervisor path uses —
just with the reclassification's own values standing in for a human's typed decision
(`reviewedBy: "system:citizen-clarification"` in the audit trail, so it's
distinguishable from a real human review). If still not confident, nothing is
force-committed — it stays paused for a supervisor, but with the fuller text now
saved either way. The form only ever appears once per submission (no open-ended
back-and-forth loop).

**A real gap found and fixed while building this**: the first working version
correctly reclassified department/category/priority on auto-resume, but the
*displayed* confidence and reasoning stayed stuck at the original low values — because
`human_review`'s resume map only ever overrode department/category/priority, never
confidence/reasoning (there was previously no path that needed to). Added
`reviewedConfidence`/`reviewedReasoning` as the same kind of optional override,
supplied only by `clarify()` (a supervisor's decision still doesn't retype a
confidence score, so that path is unaffected). Verified via a live before/after curl
comparison — confidence went from a stale `0.2` to the actual reclassification's `0.8`.

**Verified two ways**: `GrievanceWorkflowPauseResumeTest` gained 2 new deterministic
cases (mocked classifier — resolves-on-clarify and still-pending-after-clarify), and
— now that Playwright exists in this project's toolkit — an actual end-to-end
Playwright run through the real UI against the real backend, screenshotted
(`docs/images/11-clarify-pending-with-form.png`,
`docs/images/12-clarify-result.png`) and embedded in `docs/APP_WALKTHROUGH.md`.
Worked correctly on the first real run once the confidence/reasoning fix landed.

**User-reported bug, live-diagnosed, real design gap found (not a code bug):** tried
with "Things are pretty bad around town" → "There are new potholes on main st" and it
stayed pending. Reproduced directly against the exact grievance row: re-ran the
*identical* clarify call against the *same* row with the *same* text — succeeded
immediately (DOT, confidence 0.8). Confirmed genuine LLM sampling variance (the same
pattern documented throughout this project for `qwen2.5:7b`), not a bug in the new
endpoint. But the one-shot design meant a citizen who hit an unlucky round had no
recourse but to wait for a supervisor, even though a retry had a real chance of
working — so a real gap regardless of the root cause being "expected variance."

**Fix**: capped at 2 clarification attempts instead of 1 (`MAX_CLARIFICATION_ATTEMPTS`
in `citizen.ts`), not unbounded — still avoids an open-ended back-and-forth, but gives
the stochastic classifier a fair second roll. Label changes between attempts ("Can you
tell us more?" → "Try adding a bit more detail"). Verified live with Playwright:
deliberately unhelpful first attempt kept it pending and correctly brought the form
back for attempt 2; a real second attempt resolved it.

## Employee dashboard: follow-up detail was invisible, not just unlabeled

User report: "The employee dashboard view needs to be enhanced to show the follow
up comments and not just the first query from the citizen." Root cause: `clarify()`
was concatenating every follow-up straight into `grievances.raw_text`
(`"...\n\nAdditional detail from citizen: ..."`), so the data was technically all
there — but the dialog rendered it as one undifferentiated paragraph under a single
"Citizen's complaint:" label, with no visual distinction between the original
submission and anything added afterward.

**Fix, not a display patch**: stopped mutating `raw_text` at all — it now always
holds exactly what the citizen originally typed. Added a `grievance_clarifications`
table (`grievance_id`, `additional_text`, `submitted_at`) with one row per
`clarify()` call; `GrievanceWorkflowResponse` gained a `clarifications:
List<ClarificationEntry>` field alongside `rawText`. Reclassification still sees the
full picture — `clarify()` builds the combined text in-memory from the original plus
every prior clarification plus the new one, same as before, just without persisting
that concatenation back into the systems-of-record column. Avoided parsing the old
concatenated string on the frontend (fragile, and wrong layer for it) in favor of
fixing the actual data model.

`GrievanceDetailDialog` now renders "Citizen's original complaint" and a
"Follow-up detail from citizen (N)" section with each entry timestamped and visually
set apart (tertiary-container card), in both the pending-review (editable) and
read-only branches — deduplicated into one shared block above the branch instead of
copy-pasted into both, since the content was previously identical in each.

**Verified live**: `GrievanceWorkflowPauseResumeTest`'s two clarify tests updated to
assert `rawText()` stays exactly the original and `clarifications()` carries the
follow-up text — still passing (4/4). Backend restarted against the real Postgres
instance (`CREATE TABLE IF NOT EXISTS` picked up the new table with no migration
tooling needed); live `curl` through submit → clarify → status confirmed the exact
shape. Playwright end-to-end through the real UI: a citizen submission + deliberately
unhelpful follow-up, then opened in the employee dialog from both the Pending Review
tab (still-editable form) and the Department Queue tab (read-only, on an earlier
already-resolved clarified case) — both correctly show the original complaint and
follow-up as distinct labeled blocks.

## First actual visual verification — and a real bug it caught

Every frontend change all session had the same caveat: "no browser tool in this
environment, structurally verified only." That changed once real screenshots were
needed for `docs/APP_WALKTHROUGH.md` — Playwright (with a headless Chromium) was
installed into the scratchpad directory (not the project, to keep it out of the
pushed repo) and used to actually drive the running app and capture it.

**This caught a real, session-long visual bug that had shipped invisibly through
the entire Material redesign and Trends work**: every `.status-chip` and
`.priority-chip` (NEW/TRIAGED/MEDIUM/etc., used on the citizen status page, both
employee dashboard tables, and the review dialog) was rendering as a plain outlined
chip with **no color fill at all** — the CSS was setting
`--mdc-chip-elevated-container-color`, which turned out to be a real Material
token that's simply never read by a bare `<mat-chip>`'s actual rendered surface
(that chip style is M3's "assist chip," outlined/transparent by design). Confirmed
by live DOM inspection through the same headless browser, not guessed: the custom
property resolved to the correct intended color, but the element that actually
paints the visible surface (`.mdc-evolution-chip__cell`) stayed transparent
regardless — and a direct `background-color` override on that element painted
cleanly with nothing else contesting it. Fixed in `styles.scss` by having
`.status-chip`/`.priority-chip` paint `.mdc-evolution-chip__cell` and
`.mdc-evolution-chip__text-label` directly, reusing the same custom properties
(so the actual color values still only live in one place per status/priority).

Screenshots verified everything else was already working as designed —
citizen submit/status/chat, the review dialog, department queue, and Trends charts
all rendered correctly on the first real look. `docs/images/*.png` (10 screenshots,
~860KB total) are committed alongside `docs/APP_WALKTHROUGH.md`.

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

## Milestone 6: PII redaction guardrail

Named in the Milestone-0 plan (eval question #13, and 4 labeled fixtures --
GRV-074..077 in `eval-complaints.jsonl`, `expected_redaction: true`) but
never built until now: no guardrail/PII code existed anywhere in the
codebase before this pass.

**`PiiRedactionWebFilter`** (`com.aigre.guardrail`) intercepts the 3 POST
endpoints carrying citizen free text (`/grievances`, `/grievances/workflow`,
`/grievances/{id}/workflow/clarify`), buffers and rewrites the JSON request
body, and replaces SSN/credit-card/phone/email matches (via `PiiRedactor`,
narrow literal regexes) with `[REDACTED-*]` markers before the text reaches
storage or the classifier. A `WebFilter`, per CLAUDE.md's own gotcha table --
this app is WebFlux end to end, `HandlerInterceptor` doesn't apply. Only the
free-text fields (`rawText`/`additionalText`) are touched; the structured
`citizenEmail`/`citizenPhone` contact fields are untouched (legitimate
contact info, not incidental PII).

**A real gap in how to test it, worked around deliberately**: the existing
`ComplaintEvalHarnessTest` calls `GrievanceIntakeService.submit()` directly
(service layer) to avoid HTTP overhead across 91 complaints — but that means
it never exercises a `WebFilter`, which only runs on the real HTTP path. Its
4 PII-laced rows would pass through unredacted if this were the only test.
Fixed with two tests instead of one: `PiiRedactorTest` (pure unit test
against the 4 literal eval fixtures) plus `PiiRedactionWebFilterTest`, a real
HTTP-level test (`WebTestClient` against a `@LocalServerPort`-random-port
embedded server, classifier mocked) that posts through the actual filter
chain and queries Postgres afterward to confirm the *stored* `raw_text` came
out redacted.

**A real Spring Boot 4 gotcha found while building the HTTP-level test**:
`@Autowired WebTestClient` on a plain `@SpringBootTest(webEnvironment =
RANDOM_PORT)` throws `NoSuchBeanDefinitionException` -- the context-customizer
class that auto-registers that bean (`WebTestClientContextCustomizerFactory`,
lived in `spring-boot-test` in Boot 3.x) isn't present anywhere in Boot
4.0.6's jars, including the new `spring-boot-starter-webflux-test` starter
(that one only restores `@WebFluxTest`-slice support, e.g.
`WebFluxTypeExcludeFilter`). Fix: build `WebTestClient` manually in
`@BeforeEach` via `WebTestClient.bindToServer().baseUrl("http://localhost:" +
port).build()` against `@LocalServerPort` (which does still exist, just moved
to `org.springframework.boot.test.web.server.LocalServerPort`) instead of
relying on autoconfiguration.

**Verified three ways**: `PiiRedactorTest` (6/6) and `PiiRedactionWebFilterTest`
(2/2) pass; full suite re-run afterward shows no new regressions (only the
pre-existing, documented `RagEvalSuiteTest` corpus-pollution failures from
`RetrievalEvalTest` sharing a suite run, unrelated to this change -- corpus
re-seeded via `POST /ingest/reset` afterward as usual). Live end-to-end
against the real running app and real Postgres instance: posted the exact
GRV-074 SSN text to `/grievances`, queried the DB directly, got back `"My SSN
is [REDACTED-SSN] and I want to know why..."`; same live check repeated
against `/grievances/{id}/workflow/clarify` with an embedded email, redacted
correctly there too.

## Milestone 6: observability extended

Audited what "instrument every call site" (the standing design rule) actually
covered so far -- turned out to be incomplete in a way that wasn't visible
until checked directly:

- **`RetrievalService.retrieve()`** timed the LLM rerank call (one `LlmCallTimer`
  call per candidate, already fine-grained) but not the embedding call or the
  pgvector search itself -- the two steps that run *before* rerank on every
  single retrieval, completely invisible. Added `llmCallTimer.time("embed",
  ...)` and `llmCallTimer.time("vector_search", ...)`, same pattern as the
  existing call sites.
- **The streaming chat endpoint had zero instrumentation** -- despite
  "chatbot" being explicitly named as a call type in the original
  milestone-0 metrics plan item. It doesn't fit `LlmCallTimer`'s synchronous
  `Supplier<T>` shape (completes via an async callback, not a return value),
  so it needed a different mechanism: a Micrometer `Timer.Sample` created in
  `ChatController` and passed into `SseTokenStreamingHandler`, which stops it
  twice against two different timers -- once on the first `onPartialResponse`
  (`aigre.chat.time_to_first_token`) and once on `onCompleteResponse`/
  `onError` (`aigre.chat.stream_duration`, tagged `outcome`). Confirmed a
  `Timer.Sample` can be stopped more than once against different `Timer`s
  (each `stop()` just measures elapsed-since-start, it doesn't consume the
  sample) before relying on it.
- **The PII guardrail (previous entry) only logged a WARN** -- not queryable,
  not graphable. Added `aigre.guardrail.pii_redacted`, a `Counter` tagged by
  PII type and field, alongside the existing log line.
- **A real, previously-invisible gap**: `/actuator/prometheus` has been
  listed in `management.endpoints.web.exposure.include` and *documented in
  ARCHITECTURE.md as working* since milestone 1 -- but `micrometer-registry-
  prometheus` was never actually added as a dependency, so the endpoint
  404'd the entire time despite being "exposed." Only caught because
  extending this section meant actually curling the endpoint rather than
  trusting the existing doc text. Added the dependency
  (`io.micrometer:micrometer-registry-prometheus`) -- confirmed live,
  `/actuator/prometheus` now returns real `aigre_*` series in exposition
  format, actuator's own startup log went from "Exposing 3 endpoints" to
  "Exposing 4."

**Verified live** against the real running app: exercised `/chat/stream` and
`/grievances` (one with embedded PII), then confirmed all 4 new/extended
metrics populated correctly via `/actuator/metrics/<name>` and
`/actuator/prometheus` -- `aigre.llm.call`'s `call_type` tag now includes
`embed`/`vector_search` alongside the pre-existing `classification`/`rerank`;
`aigre.chat.time_to_first_token` and `aigre.chat.stream_duration` each
recorded a real sample from one chat call; `aigre.guardrail.pii_redacted`
recorded one `PHONE`/`rawText` increment. Full test suite re-run afterward:
no new regressions from this change (one unrelated flake,
`LlmGrievanceClassifierTest.pureComplimentIsNotActionable`, confirmed via
`git diff` to be untouched by anything in this pass -- the same documented
live-LLM sampling variance as every other `qwen2.5:7b` flake in this
project); corpus re-seeded via `POST /ingest/reset` after the run wiped it
again, same as every prior full-suite run.

## Duplicate detection (scenario 5) and reopened-case handling (scenario 7)

Both named in the Milestone-0 plan and both genuinely unbuilt until now (confirmed
via investigation before writing any code: `find_duplicate_chain` only ever
*read* `duplicate_of_id`, seeded by SQL, never written by the app; no priority-
tier-bump logic existed anywhere).

**Decisions confirmed with the user before starting**: (1) duplicate matching is
SQL-based (same department + category, recent window), not embedding
similarity — no structured location field exists in the schema (`raw_text` is
free text only), so "same category + location window" from the plan narrows to
department+category+time, the only structured signals actually available; (2)
scope is backend + minimal UI (an employee "Mark Resolved/Closed" action and a
citizen "Reopen" button), not a full lifecycle UI — the dashboard had no path
to `CLOSED` status at all before this pass, only the MCP tool could set it.

**Duplicate detection** (`DuplicateDetectionService`, new `com.aigre.duplicate`
package): `findOpenDuplicate(department, category, excludeId, asOf)` finds the
earliest still-open grievance (excludes RESOLVED/CLOSED/NOT_ACTIONABLE/
**DUPLICATE** itself) in the same department+category within a 7-day window
(`grievances.duplicate-window-days`, configurable). Excluding DUPLICATE-status
rows from candidacy was the key design choice: it means a freshly-created
duplicate always resolves in one hop to the true original rather than growing a
chain — the next report in the window matches the *original*, not the
duplicate, since the duplicate's own status is no longer a matchable candidate.
Confirmed live: two submissions matched a single pre-existing seeded row, not
each other.

Wired into **both** commit paths independently (they don't share code, see
milestone-4 entries above) — `GrievanceIntakeService.submit()` right before its
INSERT, and `GrievanceWorkflowGraphConfig.commit()` right before its UPDATE.
Both only check when the outcome is about to be TRIAGED (department+category
are only known/confident by then); a match flips status to DUPLICATE and skips
assigning an SLA due date, per the plan ("does not open a second SLA clock").

**Reopen** (`GrievanceMcpTools.reopenGrievance`, a 5th MCP tool, consistent
with that file already owning all grievance lifecycle mutations): only
succeeds from CLOSED status. Bumps priority one tier via a new
`Priority.oneTierUp()` (LOW→MEDIUM→HIGH→CRITICAL, capped — CRITICAL has no
tier above it), explicitly nulls `resolved_at` (a real gap found in
`update_grievance_status`: its CASE expression only ever *sets*
`resolved_at`, never clears it, so a naive reopen via that existing tool would
leave a stale resolution timestamp on an active case), recomputes
`sla_due_at` from the bumped priority and the reopen timestamp, and sets
status to `REOPENED`. Exposed over HTTP as `POST /grievances/{id}/reopen`
(citizen-facing) and a sibling generic `POST /grievances/{id}/status`
(employee "Mark Resolved"/"Mark Closed", a thin wrapper around the existing
`update_grievance_status` tool — no new backend logic needed for that half).

**A real regression this caused, caught by re-running the full suite twice in
a row**: `GrievanceWorkflowPauseResumeTest` and `GrievanceWorkflowServiceTest`
started failing — not from a logic bug, but because those tests (and my own
new ones, before I fixed them) reused fixed, realistic category strings
("general-complaint", "road-surface") and don't clean up their rows after
running. Once duplicate detection went live, a second run of the same test
matched the *first* run's still-open leftover row from the database and
landed on DUPLICATE instead of the TRIAGED the assertions expected — the
department+category-based design working exactly as intended, just against
test hygiene that was never exercised this way before. Fixed by giving the
mocked-classifier tests fresh random categories per run (same fix already
applied to my own new tests) and, for the one test that hits the *real* LLM
(`GrievanceWorkflowServiceTest`, where the category can't be made artificially
unique), loosening the assertion to accept either TRIAGED or DUPLICATE as a
valid "committed without pausing for review" outcome — which is what that
test actually checks. Verified by running the full suite twice consecutively
afterward with zero flip-flopping.

**Verified four ways**: `DuplicateDetectionServiceTest` (6 cases: matches,
category mismatch, outside window, terminal/duplicate statuses excluded,
self-exclusion, earliest-wins) against a real Postgres instance with random
per-test categories to avoid colliding with the real seeded demo data (a
first draft using realistic categories like "road-surface" collided with real
seed.sql rows on the very first run — confirmed the detection logic works
against real data, but the wrong thing for an isolated test). Two end-to-end
tests (`GrievanceIntakeDuplicateTest`, `GrievanceWorkflowDuplicateTest`) through
each commit path with a mocked classifier. `GrievanceMcpToolsTest` gained 3
reopen cases (rejects non-CLOSED, bumps priority + clears resolution + new
SLA, CRITICAL stays capped) against throwaway inserted fixtures. Live curl
end-to-end: two workflow submissions correctly converged on one existing open
duplicate; a full resolve→close→reopen cycle correctly bumped HIGH→CRITICAL,
cleared `resolved_at`, and assigned a fresh SLA date. Playwright end-to-end
through the real UI: employee "Mark Resolved" on a real TRIAGED row (table
correctly showed RESOLVED after refresh), and citizen "Reopen" on a CLOSED
lookup — caught and fixed a real UI bug in the same pass: the reopen-success
message was gated on `status === 'CLOSED'`, which becomes false the instant
reopen succeeds (status flips to REOPENED), so the confirmation could never
actually render; fixed by widening the gate to `status === 'CLOSED' ||
reopenSuccess()`.

Not built, by the explicit scope decision above: no "resolve/close" action
existed anywhere before this pass, and full lifecycle UI (routing a REOPENED
case back through a review gate, notifying the original department) is still
out of scope — the plan's "flagged for supervisor review" is satisfied by the
REOPENED status itself being visible in the department queue with its own
chip color (already existed, unused until now), not a new approval gate.

## Cross-reference-competition: fixed via corpus restructuring (option a)

Picked up the open item from the "Eval suite" section above. The user had
already installed neither JDK candidate needed for the cross-encoder
retry (option b, take 2 — still blocked by the same onnxruntime/
msvcp140.dll conflict, confirmed by re-checking for a newer JDK on this
machine before starting: none found), so this pass implemented **option
a — exclude disambiguation text from embedded chunks** — corpus-level,
code-only, no environment dependency.

**Convention**: corpus authors wrap a disambiguating clause inline in the
source `.txt` with `[[XREF]]...[[/XREF]]`. Curated 20 files to mark (not
a blind regex sweep — an initial broad grep for "distinct from"/"not
a"/"instead" matched ~90 of 108 files, almost the whole corpus, since
those phrases are common in ordinary policy prose; the actual target set
came from `corpus-manifest.md`'s deliberately-curated "distinguishes
from X" / "distractor" annotations plus the specific documents PROJECT.md
already named as causing EQ-007/EQ-010/EQ-014/EQ-017/EQ-024/EQ-062).

**A chunk needs three text representations, not two** — this was the
real mid-implementation finding. First attempt: mark spans, strip them
from the embedded vector only, keep the full original text as what's
stored/returned. Re-ran the eval suite to verify rather than assuming it
worked — **EQ-007 and EQ-024 were still failing, identically to before.**
Investigated rather than guessing why: the vector store is HYBRID
search (vector + Postgres FTS) over the *stored* text, and
`RetrievalService`'s LLM rerank step — which actually decides the final
top-1, since every `initialK` candidate gets reranked and sorted by
`rerankScore` — also scores the stored text. Both were still fully
exposed to the disambiguation content, since it was deliberately kept in
what's stored (so the answering LLM wouldn't lose it). Fix: added a
`rerank_text` metadata field at ingestion time — the same
disambiguation-stripped text used for embedding — and pointed
`RetrievalService.rerankScore()` at it instead of the returned text.
`CorpusIngestionService` had to stop using `EmbeddingStoreIngestor`
entirely to make this possible — it embeds and stores the same string by
construction, with no interception point — replaced with a manual
`splitAll()` → strip → `embedAll()` → `addAll()` loop that keeps the same
batching behavior (still needed: `EmbeddingStoreIngestor.ingest()` was
already documented to overwhelm the local Ollama embedding runner at
corpus scale without per-batch chunking).

**Verified via the same "diff the failure sets across runs" discipline
this project has used for every prior retrieval-tuning attempt** (3 full
34-case `RagEvalSuiteTest` runs, ~8 minutes each):
- Run 1 (embed-only fix): EQ-007/EQ-024 still failing exactly as before
  — the finding above.
- Run 2 (embed + rerank_text fix): **29/34 (85.3%)**, up from the
  documented 28/34 (82.4%) baseline. 5 of the 6 originally-named
  cross-reference-competition failures now pass outright (EQ-007,
  EQ-010, EQ-014, EQ-017, EQ-062). EQ-024's *specific named distractor*
  (`trash-collection-sop.txt`) no longer wins — confirmed via a direct
  chat query that it doesn't even place in the top 5 anymore — but a
  different DEP resolved-case-log document wins instead, a separate,
  already-documented failure mode (concrete narrative beating abstract
  policy prose — same family as EQ-020/EQ-061/EQ-062's prior history)
  that this fix was never targeting. Two new failures appeared: EQ-008
  (DOT's winter-road-treatment-sop.txt beating DPW's snow-removal
  policy) and EQ-011 (DOE's free-reduced-lunch-faq.txt beating DHHS's
  benefits-eligibility FAQ).
- **Investigated EQ-008/EQ-011 rather than accepting them as fix
  fallout.** Hypothesis: the one `[[XREF]]` span marked in each of those
  two specific files was itself a *helpful* self-limiting signal (a
  document honestly saying "I'm adjacent but not what you want") that
  the reranker had been using correctly, and stripping it removed that
  signal rather than removing noise. Tested by reverting just those two
  spans back to plain text and re-ingesting — **run 3 reproduced the
  identical 29/34 result, EQ-008/EQ-011 still failing, unchanged.** This
  disproved the hypothesis: those two failures aren't caused by this
  fix. Re-applied both markings (proven harmless, and consistent with
  the rest of the corpus's convention) rather than leaving the corpus in
  an inconsistent, disproven-workaround state. EQ-011 in particular
  matches a case PROJECT.md already recorded flipping during a
  completely unrelated earlier experiment (the reverted rerank-prompt
  tweak), described there as "arguably a legitimate alternate answer...
  not clearly a misretrieval" — consistent with genuine LLM-rerank
  sampling variance, the same well-documented pattern seen throughout
  this project's classifier work, not a corpus regression.
- Full backend test suite re-run afterward (not just the RAG suite):
  only the same two known categories failed — `RagEvalSuiteTest` (5/34,
  explained above) and the pre-existing, unrelated
  `LlmGrievanceClassifierTest.pureComplimentIsNotActionable` live-LLM
  flake. `RagEvalSuiteTest`'s inline per-question comments updated to
  match this verified state (fixed cases marked FIXED with the
  mechanism; EQ-008/EQ-011/EQ-020/EQ-061 documented as known findings
  with their explanation) rather than left describing the old, now-
  stale failure signatures.

**Net result: a real, verified improvement (28/34 → 29/34), with the
original bug's own mechanism resolved for 5 of 6 named cases and
meaningfully narrowed for the 6th** — not a clean sweep, and reported
that way rather than rounded up. The residual failures split cleanly
into two already-understood, separate families (resolved-case-log
competition; LLM-rerank sampling variance) neither of which this fix
was targeting.

## Real employee auth: Spring Security + JWT, department-scoped, role-gated

The last item on the open-items list, and the biggest single feature this
session. Replaces the milestone-5 department-picker stub (client-side only,
`localStorage`-persisted, no login) with real authentication tied to
`department_employees`.

**Decisions confirmed with the user before starting** (two real forks, not
silently assumed): (1) dashboard access is scoped **strictly to the logged-in
employee's own department**, server-enforced, not just "logged in or not" —
the picker goes away entirely; (2) **AGENT views, SUPERVISOR acts** — an
AGENT can see their department's queue read-only, SUPERVISOR can
additionally resume a paused review or mark a grievance resolved/closed.

**Schema**: `department_employees` gained `username`/`password_hash`
columns. No migration tool in this project (`schema.sql`'s `CREATE TABLE IF
NOT EXISTS` is a no-op against an already-existing table), so these needed
explicit `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` statements to actually
reach the existing `aigre-pg` database from earlier sessions, not just a
fresh one — same pattern as `grievances.duplicate_of_id` etc. earlier this
session. `seed.sql`'s employee INSERT gained an `ON CONFLICT (id) DO UPDATE`
specifically so it could backfill credentials onto the 12 already-seeded
rows without needing a full reseed.

**Backend** (`com.aigre.auth`, new package): `JwtService` issues/validates
JWTs (`io.jsonwebtoken` 0.12.7 — exact API confirmed via `javap` against the
resolved jar before writing code, not assumed, since 0.12.x renamed several
methods from 0.11.x's `setSubject()`-style builder to `subject()`-style).
Signing key is a fixed configured secret (`application.yml`), not
per-restart-random — a random key would log every employee out on every
backend restart, which happens constantly in this dev workflow.
`JwtAuthenticationWebFilter` validates the bearer token and populates the
reactive `SecurityContext`; registered directly inside `SecurityConfig`'s
`SecurityWebFilterChain`, deliberately **not** `@Component`-annotated — a
plain `WebFilter` bean would also get picked up by WebFlux's own generic
filter registration, running outside Spring Security's filter ordering and
context propagation entirely (same category of gotcha already documented on
`PiiRedactionWebFilter`, for a different reason: no `@Component`, not
because embedded vs. rerank text differ). `POST /auth/login` validates
against a bcrypt hash (`spring-boot-starter-security`'s `PasswordEncoder`)
and returns a token + the employee's name/department/role.

**Department scoping is the actual point, not incidental**:
`GrievanceQueryController.list()` derives its department filter from the
authenticated principal directly, never a client-supplied query parameter —
this is what makes it "strictly own department" rather than just "logged
in": a DOT employee's valid token can't be used to request `?department=DPW`
by hand. The four endpoints that act on one grievance by ID (view/resume the
paused workflow, mark resolved/closed) additionally compare that
grievance's own department against the principal's
(`DepartmentAccess.requireOwnDepartment`, a small static utility, 4 call
sites) before allowing anything — role rules alone don't stop an
authenticated employee from reaching another department's grievance by ID,
only from reaching the mutation endpoints at all.

**A real bug caught before it shipped, not after**: `SecurityConfig`'s
`pathMatchers(GET, "/grievances/{id}")` is `permitAll()` for the citizen
status lookup — but `{id}` matches *any* single path segment, including the
literal string `"trends"`, which would have made the employee-only
`GET /grievances/trends` endpoint silently public. Caught by testing the
actual URL against the running app rather than assuming the rule was scoped
correctly (`authorizeExchange` matches in declaration order, first match
wins), fixed by adding a more-specific `/grievances/trends` rule ordered
before the wildcard one, with a comment explaining why the ordering matters
so it isn't reintroduced. Added a regression test for this exact case
(`SecurityIntegrationTest.trendsEndpointIsNotAccidentallyPublicViaTheGrievanceIdPattern`).

**A second real bug, live-diagnosed**: after wiring Spring Security in,
`POST /auth/login` started failing from the Angular dev server with a CORS
preflight error, even though `/auth/login` is `permitAll()`. Root cause:
`WebConfig` (a `WebFluxConfigurer` bean, milestone 5) registered CORS at the
WebFlux routing layer, but Spring Security's filter chain now sits in front
of *all* requests, including the CORS preflight `OPTIONS` request itself —
by the time a request would reach WebFlux's own CORS handling, Security had
already rejected the unauthenticated preflight. Fixed by moving CORS
configuration into `SecurityConfig` itself (`.cors(...)` wired to an
explicit `CorsConfigurationSource` bean, plus an explicit
`permitAll()` on all `OPTIONS` requests) and deleting the now-fully-
superseded `WebConfig.java` — one source of truth instead of two competing,
differently-timed ones.

**A third, smaller finding**: Spring Boot hides exception messages from
error responses by default (`server.error.include-message`), which is
usually the right call for a real deployment but meant the frontend only
ever saw a generic "Unauthorized" instead of "Invalid username or
password." on a failed login. Tried the config property first — confirmed
live it had no effect on this app's WebFlux error responses (not chased
further; a login-page message isn't worth debugging Boot's reactive error-
attribute internals for). Fixed more directly instead: `AuthController`
catches the failure itself and returns an explicit JSON body with a
`message` field, sidestepping the question of what Boot's default renderer
does or doesn't include.

**Frontend**: `AuthService` (signal-based session state, `sessionStorage`-
persisted rather than `localStorage` — cleared on tab close rather than
persisting indefinitely), an `authInterceptor` (attaches the bearer token to
every request when present; also catches a 401 from an *already*-
authenticated session and bounces to `/login`, so an expired token doesn't
just leave the dashboard silently broken), an `authGuard` protecting
`/employee`, and a new `/login` page. `GrievanceDetailDialog` and
`Employee` both read `auth.isSupervisor()` to hide actions an AGENT can't
use anyway — a UX nicety, not the actual enforcement (the backend's role
and department checks are what actually matter; the frontend gate just
avoids showing a control that would 403).

**Verified four ways**: `JwtServiceTest` (issue/parse round-trip, wrong-
secret rejection, expired-token rejection — pure unit tests, no Spring
context). `SecurityIntegrationTest` (9 cases against a real embedded server
and the real seeded employees: login success/failure, unauthenticated 401,
AGENT 403 on a mutation, cross-department 403 for a SUPERVISOR acting
outside their own department, the trends-path regression guard above). Live
curl end-to-end: login, AGENT list success, AGENT mutation 403, SUPERVISOR
same-department success, SUPERVISOR cross-department 403, bad-password 401
— all confirmed against the real running app and real Postgres instance
before any frontend work started. Playwright end-to-end through the real
UI: unauthenticated redirect to `/login`, AGENT login showing a
department-scoped read-only dashboard with no action buttons, SUPERVISOR
login on the *same* seeded case showing the full action set, logout
returning to `/login`, and a bad-password attempt showing the real error
message inline. Full backend test suite re-run afterward (94 tests now, up
from 81): only the already-documented `RagEvalSuiteTest` findings (5/34,
unrelated to this change) — no new regressions from wiring Spring Security
into an app that previously had none.

## Sentiment trend chart: numeric average → 5-level confidence scale
The Trends tab's sentiment chart originally plotted `AVG(sentiment_score)`
per day as a line on a fixed -1..1 axis — a single averaged number that
couldn't show same-day spread (100 grievances split evenly across the
range looks identical to 100 all landing in the middle). Replaced with a
5-band ordinal scale — No Confidence / Low Confidence / Neutral / Moderate
Confidence / High Confidence, splitting -1..1 into five even 0.4-wide bands
— rendered as a stacked bar chart, one bar per day, segment height = count
of grievances in that band that day.

New `com.aigre.query.SentimentLevel` enum is the single source of truth for
the band boundaries (mirrors the existing `Priority` enum pattern), so the
boundary numbers exist in exactly one place rather than being duplicated
between Java and SQL. `DailySentiment(date, avgSentiment)` was replaced
outright by `DailySentimentLevels(date, noConfidence, lowConfidence,
neutral, moderateConfidence, highConfidence)` — its sole consumer
(`TrendsResponse.sentimentByDay`) was confirmed before deleting the old
record. `GrievanceTrendsService`'s new query reuses the `COUNT(*) FILTER
(WHERE ...)` pattern already established in the same file for
`SlaSnapshot`, rather than introducing a new query style.

Frontend: `sentimentChartData` is now a `ChartConfiguration<'bar'>` with 5
datasets (`stacked: true` on both axes), a 5-color diverging palette
(red→amber→grey→green→dark-green), and a bottom legend — the other 3
Trends charts hide their legend since they're single-series, this one
needs the key.

**Verified**: `GrievanceTrendsServiceTest`'s fixtures extended to cover all
5 bands (added a No-Confidence and a High-Confidence row; the existing
-0.2 fixture sits exactly on the Low/Neutral boundary, confirmed
intentional — bands are `[lower, upper)` so it lands in Neutral, not Low).
Live curl of `GET /grievances/trends` against the real seeded DOT data
confirmed the new response shape. Playwright screenshot of the live chart
confirmed the stacked bars, legend, and colors render correctly against
real data (thin slivers for Moderate/High Confidence are expected — most
seeded complaint text skews negative-to-neutral sentiment). Full backend
suite re-run clean (only the pre-existing `RagEvalSuiteTest` LLM-rerank
variance, unrelated).

## Email ingestion: second inbound channel via IMAP polling
`com.aigre.email.EmailGrievancePoller` polls a monitored IMAP mailbox on a
schedule (`@Scheduled`, off by default via `email.enabled: false`) and
feeds each unread message through the *exact same*
`GrievanceWorkflowService.start(request, channel)` entry point the citizen
portal uses — same classification, duplicate detection, human-review
pause, SLA computation. `GrievanceWorkflowService.start(request)` (the
portal's call) now delegates to a channel-aware overload with `"PORTAL"`;
the poller calls the same overload with `"EMAIL"`. The `grievances.channel`
CHECK constraint already allowed `'EMAIL'` since Milestone 0 — only the two
`INSERT` literals needed to stop hardcoding `'PORTAL'`.

**Chose IMAP polling over an inbound-webhook provider (SendGrid/Mailgun)
or a self-hosted SMTP receiver** — this project runs fully offline-capable
against local infra with no cloud account dependencies; a webhook needs a
public HTTPS endpoint and a third-party account, a self-hosted SMTP
receiver needs MX/DNS/firewall setup. IMAP polling needs neither: a
scheduled job, mailbox credentials, and (for tests) an embedded fake mail
server.

**A gap caught before it shipped**: `PiiRedactor` was only wired in via
`PiiRedactionWebFilter`, a WebFlux filter that redacts PII out of the HTTP
request body before any controller sees it. The email poller never goes
through HTTP — it calls `GrievanceWorkflowService` directly from a
scheduled job — so without an explicit call, an emailed complaint would
skip redaction entirely and silently break parity with the portal path.
Fixed by having the poller call `PiiRedactor.redact(...)` itself before
building the `GrievanceIntakeRequest`.

Idempotency comes from the IMAP SEEN flag (no separate cursor table): only
unseen messages are fetched, and a message is marked SEEN only after
`start()` succeeds. A message that fails to ingest is moved to a `Failed`
IMAP folder instead of retrying forever on every poll.

**Two real bugs caught during verification, not assumed away**:
1. The poller hardcoded the `imaps` (implicit TLS) protocol, but
   GreenMail's test IMAP server is plain `imap` — the test failed with an
   SSL handshake error. Fixed by making the protocol configurable
   (`email.imap.protocol`, defaults to `imaps` for real mailboxes, since
   most providers require it).
2. A concurrency race only visible in the *full* suite run, not in
   isolation: with the Spring context alive for the suite's ~500s runtime,
   the app's own real `@Scheduled` trigger (default 60s) fired in the
   background against the same live context and raced the test's manual
   `poll()` calls, double-ingesting a message before either call could mark
   it SEEN. Fixed by pushing the test's `email.poll-interval-ms` out to an
   hour via `@DynamicPropertySource`, comfortably longer than any suite
   run, so only the test's own manual calls ever fire.

Also found and fixed a pre-existing-pattern test-hygiene bug of my own
making: the idempotency test matched grievances by a fixed literal body
string (`LIKE '%Broken streetlight%'`), which accumulated false matches
across repeated runs against the same persistent dev Postgres instance.
Fixed by switching to a unique per-run marker — the same convention
`GrievanceWorkflowPauseResumeTest` already uses (random category per run)
instead of adding cleanup machinery this test suite doesn't otherwise use.

**Verified**: `EmailGrievancePollerTest` (2 cases, embedded GreenMail
fake mailbox — no real SMTP/IMAP infra needed) green in isolation and
within the full suite. Regression run of every test touching
`GrievanceWorkflowService.start()` (`GrievanceWorkflowServiceTest`,
`GrievanceWorkflowPauseResumeTest`, `GrievanceWorkflowDuplicateTest`,
`GrievanceIntakeDuplicateTest`, `GrievanceMcpToolsTest`) confirmed the
channel-aware overload didn't change portal behavior. Live curl of
`POST /grievances/workflow` against the restarted backend confirmed the
portal path still works end-to-end post-change.

## ADMIN role: cross-department oversight
Every seeded employee (AGENT/SUPERVISOR) was strictly single-department by
design — `department_employees.department_id` a required-in-practice FK,
enforced server-side by `DepartmentAccess.requireOwnDepartment` and by
`GrievanceQueryController` deriving its department filter from the
authenticated principal, never a client-supplied value. There was no
account that could browse or act on grievances across departments. Added
a third role, ADMIN, for exactly that: a `department_employees` row with
`department_id = NULL`.

**Two pieces had to agree, only one needed an actual code change.**
`GrievanceQueryService.list()` already built its `WHERE` clause
conditionally — a `null`/blank department meant "no filter," entirely by
coincidence of how the department-scoping feature was written, not
because anyone anticipated an admin role. So once `SecurityConfig` allows
the ADMIN role through at all, its queue/pending-review listings are
already correct for free. The one genuine gap: `DepartmentAccess.
requireOwnDepartment` rejects *any* principal whose `departmentId` doesn't
equal the grievance's department — including a `null` one — so ADMIN
needed an explicit bypass (`if (principal.isAdmin()) return;`) at the top
of that method, or a cross-department admin couldn't act on anything
either. `SecurityConfig`'s `hasRole("SUPERVISOR")` on the two mutation
endpoints (resume, mark resolved/closed) became `hasAnyRole("SUPERVISOR",
"ADMIN")`. `EmployeePrincipal.isSupervisor()` now also returns true for
ADMIN, since it's used purely for UI-gating which action buttons render —
the real enforcement is the `hasAnyRole` rule, not this method.

**Schema**: `department_employees.department_id` was already nullable
(no `NOT NULL` in the original `CREATE TABLE`) — only the `role` CHECK
constraint needed widening to admit `'ADMIN'`, via the same
`ALTER TABLE ... DROP CONSTRAINT IF EXISTS / ADD CONSTRAINT` pattern this
project already uses for schema changes against an existing `aigre-pg`
database (no migration tool here — `schema.sql`'s `CREATE TABLE IF NOT
EXISTS` is a no-op against a table that already exists).

**Frontend**: `EmployeeSession.departmentId` widened to `string | null`,
`role` widened to include `'ADMIN'`. A new `auth.isAdmin()` computed
signal drives one UI difference beyond the shared `isSupervisor()` action
gating: the Pending Review and department-queue tables gain a
`department` column, shown only for ADMIN, since that's the only role
whose queue can span more than one department at a time and rows would
otherwise be indistinguishable. The dashboard header/tab labels show "All
Departments" instead of piping a `null` department through
`DepartmentNamePipe` (which would otherwise render "—").

**Seeded as** `ops.admin` / `Demo1234!` (same shared demo password as
every other seeded account) — see `RUNNING.md`'s credentials table.

**Verified**: two new `SecurityIntegrationTest` cases — login returns
`departmentId: null` and `role: "ADMIN"`; ADMIN can act on a DPW
grievance via `POST /grievances/{id}/status` that the existing
`supervisorCannotActOnAnotherDepartmentsGrievance()` test proves a DOT
supervisor is forbidden from (same grievance ID, opposite expected
status code) — the two tests are a direct before/after pair. Live
Playwright pass confirmed the dashboard shows "All Departments" framing,
lists rows spanning multiple departments with a visible department
column, and that opening a grievance detail dialog shows the same
Mark Resolved/Mark Closed controls a SUPERVISOR gets. Full backend suite
re-run clean.

## Single-origin static hosting + Cloudflare Tunnel (internet access, dev machine stays local)

Goal: let the user reach the app from outside their own network while it keeps
running only on their PC, without deploying anywhere. Decided on a Cloudflare
quick tunnel over ngrok/port-forwarding — no router/firewall changes, no
exposed home IP, free, and (unlike ngrok's free tier) not time-boxed per
session; the tradeoff is the quick-tunnel URL is random and changes every
`cloudflared` restart, which the user explicitly accepted over the
account+domain-required "named tunnel" alternative for a stable URL.

This surfaced a real architecture gap first: `api.service.ts` hardcoded
`API_BASE = 'http://localhost:8085'`, so tunneling the frontend alone would've
had every remote visitor's browser try to hit *their own* localhost:8085.
Fixed by making `API_BASE` a relative empty string and serving the built
Angular app from the Spring Boot backend itself (`com.aigre.config.SpaWebFluxConfig`,
a `WebFluxConfigurer` resource handler on `/**` with an `index.html` fallback for
any path that isn't a literal static file — needed so a direct load or refresh
on `/employee`/`/citizen/xyz` doesn't 404, since those are Angular client-side
routes, not server resources). `SecurityConfig` permits GET on the app shell's
own paths (`/`, `/login`, `/citizen/**`, `/employee/**`, static asset
extensions) alongside the existing API rules — this has no bearing on employee
auth, which is still enforced by the API calls those pages make, not by the
page load itself.

For `ng serve` to keep working in dev with a relative `API_BASE`,
`frontend/proxy.conf.json` (wired into `angular.json`'s
`serve.options.proxyConfig`) proxies `/grievances`, `/auth`, `/chat`,
`/ingest`, `/mcp`, `/actuator` to `localhost:8085` server-side — the browser
never sees a cross-origin request either way, dev or single-origin-prod.

Process now: `ng build` → copy `dist/frontend/browser/*` into
`src/main/resources/static/` → restart the backend → the whole app is
reachable at `localhost:8085` alone → `cloudflared tunnel --url
http://localhost:8085` exposes it. Verified live: login, a direct deep-link
load of `/employee` (simulating a refresh), and an API call (`/auth/login`)
all returned 200 through the actual `https://*.trycloudflare.com` URL, not
just localhost.

The static copy is a snapshot, not a live rebuild — RUNNING.md's new
"Exposing the app to the internet" section notes it needs re-running after
any frontend change.

### Follow-up: two real bugs only surfaced by testing from an actual second machine

All of the above passed local verification (curl, Playwright against
`localhost:8085`) but the app was still blank/broken for the user
accessing the live `*.trycloudflare.com` URL from another PC — a good
reminder that `localhost` testing can't exercise cross-origin behavior at
all, since every request made *from* localhost carries an Origin (or no
Origin) that never triggers the checks a real remote browser triggers.

1. **CORS rejected every JS asset.** Browsers fetch `<script
   type="module">` (what the Angular build emits) in CORS mode, sending an
   `Origin` header even for a same-origin load. `SecurityConfig`'s
   `corsConfigurationSource()` only allowed `http://localhost:*` — so
   through the tunnel, every `chunk-*.js`/`main-*.js` request got an empty
   403 straight from Spring's `CorsProcessor`, before even reaching the
   permitAll rules, and the page rendered blank with no visible network
   error explaining why (curl doesn't send an Origin header, which is why
   this passed every curl-based check). Root-caused from the response
   headers alone: `Vary: Origin, Access-Control-Request-Method, ...` and
   `content-length: 0` are Spring's CORS-rejection signature, not
   Cloudflare's. Fixed by adding `https://*.trycloudflare.com` to the
   allowed origin patterns — the random subdomain changes every
   `cloudflared` restart but the suffix doesn't, so this doesn't need
   updating per-session. Reproduced and confirmed fixed with Playwright
   driving a real browser against the actual tunnel URL (not localhost) —
   the only way this class of bug shows up at all.
2. **`auth.service.ts` had its own separate hardcoded `API_BASE`.** The
   single-origin fix above only touched `api.service.ts`'s constant;
   `auth.service.ts` predates sharing an HTTP concern between the two
   files and never got the same edit, so `login()` kept POSTing to
   `http://localhost:8085/auth/login` literally — which resolved to the
   *visiting browser's own machine*, not the tunnel host, and failed with
   a CORS/loopback-address-space error specific to that mismatch. Grepped
   the whole `frontend/src` for the literal string after fixing this one
   to confirm no third copy exists.

## Paused-review visibility bug: fixed

The gap logged above (a low-confidence submission was invisible in every
department's Pending Review queue, and even 403'd on direct-by-ID access,
for the entire time it sat paused) is now fixed.

Root cause confirmed via `Explore` agent research before touching any code:
`GrievanceWorkflowGraphConfig`'s `classify` node computes the LLM's guess
(`predictedDepartment`/`finalCategory`/`finalPriority`/`confidence`/
`sentimentLabel`/`sentimentScore`) entirely in LangGraph4j's in-memory state
and never writes it to Postgres — the *only* node that touches the
`grievances` table at all is `commit`, which doesn't run until after a
human resumes a paused review (`interruptBefore(HUMAN_REVIEW_NODE)` pauses
execution before `human_review`, and `commit` only follows that). So
`department_predicted` stayed `NULL` in the DB for the entire pause window,
even though the classifier's real guess already existed — it just hadn't
been persisted yet.

**Fix**: `GrievanceWorkflowGraphConfig.persistPredictedClassification()`, a
new small `UPDATE` fired from inside `classify()` itself (so it runs
regardless of which branch — straight to `commit` or via `human_review` —
the graph takes next), writing `department_predicted`/`category`/
`priority`/`classification_confidence`/`sentiment_label`/`sentiment_score`
immediately. Deliberately does **not** touch `department_confirmed` or
`status` — those stay `commit`'s responsibility alone, preserving the
AI-guess-vs-human-confirmed audit distinction the schema was already built
around. `priority` goes through the same `resolvePriority()` defensive
fallback `commit()` uses, since the column has a `CHECK` constraint and a
raw unvalidated LLM string could violate it.

**Verified**:
- New regression test,
  `GrievanceWorkflowPauseResumeTest.pausedGrievanceWithADepartmentGuessIsVisibleInThatDepartmentsQueueBeforeAnyResume`
  — mocks the classifier to return a real department at low confidence
  (live LLM sampling can't reliably reproduce "has a guess, but under the
  confidence threshold" on demand for a manual test, confirmed by trying
  twice against the running app and getting a fully-null guess both times),
  asserts `pendingReview` is true immediately after `service.start()`, then
  asserts `GrievanceQueryService.list("DOT", null)` — the exact query the
  bug report named — already contains the row, with no resume having
  happened yet.
- Live, via the actual running app: submitted through `POST
  /grievances/workflow`, confirmed `classification_confidence`/
  `sentiment_label` land in the DB immediately post-classify (proving the
  new write fires); then, since that particular live text happened to get a
  fully-null department guess from the classifier (not a bug — matches the
  regression test's own note above), patched `department_predicted` on that
  row via SQL to simulate a real "has a guess, low confidence" case and
  confirmed both halves of the original bug report are gone: `GET
  /grievances/{id}/workflow` as a DOT supervisor now returns 200 (was 403),
  and the row appears in `GET /grievances?status=NEW` for that department.
- Full backend suite re-run clean after the change.

## Postgres-backed LangGraph4j checkpointing: paused reviews now survive a restart

Picked up the open item below. `GrievanceWorkflowGraphConfig`'s `MemorySaver` kept
every checkpoint in that JVM's heap — a paused human-review workflow was lost
entirely on an app restart, even though the grievance row itself was already fine
(the earlier "Paused-review visibility bug" fix ensures `department_predicted` etc.
land in Postgres immediately at classify-time). Swapped in
`langgraph4j-postgres-saver:1.8.20` — an exact version match to the
`langgraph4j-core:1.8.20` already pinned via `pom.xml`'s `${langgraph4j.version}`
property, confirmed via Maven Central's `maven-metadata.xml` before adding it
(per CLAUDE.md's version-sensitive-dependency rule) — and its actual source at git
tag `v1.8.20` was read directly rather than trusted from a search summary, since an
AI-generated web search result for this exact artifact had already produced a wrong
groupId/version pairing during research.

**Change is small and confined**: `GrievanceWorkflowService.java` needed zero
changes — it only ever calls `graph.invoke(...)`/`GraphInput.resume(...)`/
`graph.getState(config).next()` against the injected `CompiledGraph` bean and has no
idea which checkpoint saver backs it. `GrievanceWorkflowGraphConfig.java` gained a
`DataSource` constructor parameter (the same Boot-autoconfigured Hikari bean
`RagConfig` already reuses for `PgVectorEmbeddingStore`) and now builds
`PostgresSaver.builder().datasource(dataSource).stateSerializer(graph.getStateSerializer())
.createTables(true).dropTablesFirst(false).build()` instead of `new MemorySaver()`.
Passing `.datasource(dataSource)` explicitly matters: without it, `PostgresSaver`
falls back to its own unpooled `PGSimpleDataSource` (a new raw connection per
checkpoint read/write) instead of the app's connection pool. `.stateSerializer(graph
.getStateSerializer())` reuses the exact `ObjectStreamStateSerializer` the
`StateGraph` constructor already builds internally, rather than constructing a
second one by hand. `createTables(true)` is idempotent (`CREATE TABLE IF NOT
EXISTS`) and runs every startup, same cadence `schema.sql` already runs at — added a
one-line comment there noting the two tables it creates (`lg4jthread`,
`lg4jcheckpoint` — Postgres folds the library's unquoted PascalCase names to
lowercase) are owned by `PostgresSaver`, not `schema.sql`, mirroring the file's
existing note about `rag_documents` being owned by `PgVectorEmbeddingStore`.
Deliberately did **not** set `CompileConfig.releaseThread(true)` — it already
defaults to `false`, and turning it on would let the framework soft-delete a
thread's checkpoint history, which is the opposite of what "survive a restart" is
asking for.

**Verified with a test that would have failed under the old `MemorySaver`, not just
one that still passes under the new saver**: added
`GrievanceWorkflowPauseResumeTest.pausedWorkflowResumesAgainstAnIndependentlyConstructedGraph_provingItSurvivesARestart`.
After a workflow pauses via the real `service`/singleton bean, it hand-constructs a
**second, fully independent `GrievanceWorkflowGraphConfig`** (bypassing Spring's
singleton bean cache entirely) and calls `.grievanceWorkflowGraph()` on it directly
— a brand-new `PostgresSaver`/`CompiledGraph` object with zero shared in-memory
state with the one `service` uses, since `PostgresSaver` has no in-process cache
(confirmed in source: only a `ReentrantLock` for thread-safety, every read/write is
real JDBC against `lg4jthread`/`lg4jcheckpoint`). Resuming against that second graph
object only succeeds if the checkpoint really came from Postgres — this is
genuinely equivalent to a restart, not a simulation that happens to pass for an
unrelated reason: the identical test against a fresh `new MemorySaver()` would fail,
since that saver's state is just an empty in-memory map with no knowledge of a
different `MemorySaver` instance's checkpoints. The test also asserts the second
graph's own `getState(config).next()` equals `HUMAN_REVIEW_NODE` *before* resuming
(proves the paused checkpoint was independently read back, not just that resume
happened to work) and checks `grievances` directly via SQL after resuming, not only
through `GrievanceWorkflowResponse` — so the test isn't solely trusting the same
response-building code path every other test in this file already exercises. All 6
`GrievanceWorkflowPauseResumeTest` cases and the single `GrievanceWorkflowServiceTest`
case pass. Direct `psql` against `aigre-pg` after the run confirmed real rows in both
new tables. Full backend suite re-run clean afterward too — the only failures were
the already-documented, pre-existing `RagEvalSuiteTest` LLM-rerank sampling
variance (5/34, exact same named cases as prior runs), unrelated to this change
(that suite never touches workflow/checkpointing code).

**Live restart verification, not just the simulated one above**: started the app,
`POST /grievances/workflow` with deliberately vague text → paused
(`pendingReview: true`, confidence 0.2). Confirmed 2 rows in `lg4jcheckpoint` for
that thread. **Killed the process entirely** (both the Maven launcher and the real
`AigreApplication` JVM) and started a brand-new one — a genuinely different PID,
not a simulation. `POST /grievances/{id}/workflow/resume` against the *new* process
returned `200`/`TRIAGED` with the reviewer's department/category/priority/SLA due
date all correctly applied. Direct SQL after confirmed the `grievances` row matches
and `lg4jcheckpoint` grew from 2 to 4 rows for that thread (the new process's own
`human_review`/`commit` steps writing on top of what the *dead* process had
written) — the checkpoint the old process wrote was read back correctly by a
process that never held it in memory.

**Known upstream quirk, not an AIGRE bug**: found while reading `PostgresSaver`'s
source end to end — `insertCheckpoint()` always writes `parent_checkpoint_id` as
`NULL`, despite the column existing; this version of the library never actually
populates checkpoint lineage. Doesn't affect anything AIGRE does today (every
`getState`/resume only ever needs "the most recent checkpoint for this thread",
never a parent-chain walk), so nothing to fix — but worth remembering if a future
LangGraph4j feature (checkpoint rollback, time-travel, branching) is ever wanted:
it wouldn't work correctly against this saver version until upstream populates
that column.

## Dark mode

Picked up the open item below. Confirmed the redesign pass's own prediction before
writing any code, per this project's habit of verifying rather than assuming:
compiled `styles.scss` directly (`sass --load-path=node_modules`) and grepped the
output — Angular Material 21's `mat.theme()` mixin was **already** emitting every
`--mat-sys-*` custom property wrapped in the CSS `light-dark()` function (e.g.
`--mat-sys-background: light-dark(#faf9fc, #121316)`), entirely dormant behind one
hardcoded `color-scheme: light;` line on `body`. So ~95% of the app needed zero new
theming work — the actual scope was: that one line, 4 hand-picked chip hex colors
outside the token system, one hardcoded toolbar shadow, and Chart.js's color
config in Trends (the one place that can't consume CSS custom properties at all,
since it renders to canvas, not through the cascade).

**Decisions confirmed with the user before building**: a three-way light/dark/system
toggle (not a plain two-state flip), as an always-visible toolbar icon+menu.

**`ThemeService`** (`frontend/src/app/core/theme.service.ts`, new): signal-based,
mirrors `auth.service.ts`'s only existing storage precedent (namespaced key,
module-level `loadStored...()` helper) but `localStorage` instead of
`sessionStorage` — a display preference should survive a restart, unlike a login
session. A `resolvedMode` computed collapses `'system'` down to the OS's live
preference via `matchMedia('(prefers-color-scheme: dark)')` (with a `change`
listener, so it updates without a reload if the OS setting flips while the tab is
open); an `effect()` — the right tool specifically because this is synchronizing
with the DOM, not Angular state — sets `document.documentElement.style
.setProperty('color-scheme', ...)` whenever the resolved mode changes.

**FOUC prevention**: a small inline script in `index.html`'s `<head>`, before any
stylesheet paints, reading the same `localStorage` key and setting `color-scheme`
synchronously. Since this is a pure client SPA the CSS-only default (no explicit
`color-scheme`) already follows the OS setting with zero JS — the gap this actually
closes is narrower: once someone has explicitly overridden their OS setting, only
`ThemeService`'s constructor can apply that (needs the bundle parsed/DI
bootstrapped/first CD cycle run), long enough to flash light-then-dark on every
reload without this. Same minimal pattern GitHub/Docusaurus/VitePress use.

**The two hardcoded chip pairs** (`.priority-high`, `.status-resolved`/
`.status-closed` in `styles.scss` — a documented earlier fix for `<mat-chip>` not
reading `--mdc-chip-elevated-container-color` on its own rendered surface) got
wrapped in `light-dark()` in place, anchored to real generated M3 tones rather than
guessed: `--mat-sys-tertiary-container` itself compiles to
`light-dark(#ffddbb, #673d00)` (tone 90→30), already used by `priority-medium`/
`priority-low`; the light-mode "high" pair deliberately used paler/darker custom
values than that standard container to stay visually distinct from medium/low
despite sharing the amber family, so the dark values are one tone-step off the
standard flip in the same direction, preserving that relationship. The green
status pair has no M3 seed to anchor to (this project's palette has no green) — both
light and dark values there are hand-picked, chosen for comparable contrast.

**Chart.js recoloring** (`trends.ts`) was the biggest piece, not gold-plating: the
old module-level color constants became two frozen palettes (`LIGHT_PALETTE`/
`DARK_PALETTE`) plus a `computed()` picker keyed off `ThemeService`. The
`primary`/`CRITICAL`/`HIGH`/tick-text dark values aren't invented — they're the
actual compiled dark-mode values of `--mat-sys-primary`/`--mat-sys-error`/
`--mat-sys-tertiary`/`--mat-sys-on-surface-variant`, i.e. exactly what the rest of
the app already uses for the equivalent role. `LOW` was dropped one tone (60→50)
rather than reused as-is, since `MEDIUM` jumps to a much brighter tone-80 in dark
mode and keeping `LOW` at its old tone would leave too little contrast between the
two. Verified `ng2-charts`' actual source before assuming live repaint would work:
`[data]`/`[options]` are plain `@Input()`s consumed via `ngOnChanges`, which takes
an `Object.assign(...) + chart.update()` path (not a full destroy/recreate) as long
as `type` doesn't change — so making the color constants and `barOptions`/
`lineOptions`/`sentimentOptions` themselves `computed()`s was sufficient for charts
to repaint live on toggle, no manual `.update()` call or `@if`-keying trick needed;
this rides the exact mechanism already making the charts update live when trends
data loads. Also fixed a **pre-existing light-mode gap**, not just a dark-mode one,
in passing: `barOptions`/`lineOptions` set no explicit tick/legend/gridline color
before this, silently falling back to Chart.js's own default — both palettes now
set one explicitly.

**Verified as not needed, not just assumed**: CDK overlay scoping (`mat-menu`,
`mat-dialog`) — grepped CDK's overlay source directly, the overlay container
attaches to `document.body`, a descendant of `<html>`, and both `--mat-sys-*` and
`color-scheme` are inherited CSS properties, so dynamically-appended overlays
inherit them with zero extra work; confirmed via a full `src` grep for
`cdk-overlay` that no pre-existing custom overlay styling exists that could create
a gap.

**Verified live**, not just via `ng build`: Playwright against `ng serve`, covering
landing, citizen portal + chat, employee dashboard (pending review, department
queue with priority/status chips, the grievance detail dialog), and all 4 Trends
charts. Confirmed: the toggle actually changes `color-scheme` and body background;
a real chip's resolved background color changes; **charts repaint live while
Trends stays open** (toggled dark→light in place, no reload, screenshotted
before/after — both render correctly with no stale-color artifacts); the
preference survives a reload (`localStorage`, not `sessionStorage`); "system" mode
follows an emulated OS dark-mode change. Screenshots of the dashboard, the
recurring-issues chip badges, the grievance detail dialog, and all 4 charts in dark
mode all read as a polished, intentional dark theme on inspection — proper
contrast throughout, no illegible text, no leftover light-mode artifacts.
`ng build` clean (no new type errors from the `computed()` conversions).

## Local model comparison: qwen2.5:7b vs. qwen3:30b-a3b vs. qwen3:8b vs. qwen3.5:9b

Explored whether a newer/larger local Ollama model could close the accuracy
gap to Claude Sonnet 5 (95.6%, see "Provider comparison" above) without
leaving the offline/no-cost default. Hardware: RTX 3060 Ti, 8GB VRAM.

**`qwen3:30b-a3b` (30.5B MoE, 3B active) — never completed a run.** Loaded
at 22GB against 8GB VRAM, Ollama split it 71%/29% CPU/GPU, and individual
classification calls blew past both a 120s and (after raising it) a 600s
timeout — 3 attempts, all `HttpTimeoutException`, one run burning 37 minutes
before failing. Root-cause finding worth keeping: **the MoE "3B active"
figure describes compute per token, not memory residency** — expert
selection is data-dependent per token, so all 30B parameters must be
resident somewhere regardless of how few are active on a given forward pass.
On a GPU too small to hold the whole model, MoE is *worse* than a dense
model of similar total size, not better — a dense model's weights are the
same every token (predictable, cacheable), while a MoE's CPU/GPU-split
experts get shuffled per token, thrashing across the slow device boundary.
Abandoned; not hardware-compatible on this machine at any timeout setting.

**Real fix found along the way, applied generally:** `ollama ps` showed
every qwen3-family model defaulting to a 32768-token context window (the
model's own max, not something AIGRE requested) — and classification is
single-shot (system prompt + one complaint, no chat history, no RAG context
injected), never needing more than a few thousand tokens. That unused
32K-context KV-cache reservation was independently pushing `qwen3:8b` (5.2GB
weights, should fit easily) to 10GB loaded with a 38%/62% CPU/GPU split.
Fixed two ways: added `ollama.num-ctx` (default `4096`) wired through
`LlmProviderConfig`'s `OllamaChatModel`/`OllamaStreamingChatModel` builders
via `.numCtx(Integer)` — confirmed to exist on both builders via `javap`
against the real `langchain4j-ollama-1.18.0.jar` rather than assumed; and
enabled Ollama server-side flash attention + `q4_0` KV-cache quantization
(`OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q4_0`), which shrank the
context-memory overhead further with no measured quality loss. Both kept as
permanent config — they help regardless of which model is active. Important
distinction confirmed by this investigation: **KV-cache quantization and
weight quantization are orthogonal.** It didn't save `qwen3:30b-a3b` (the
*weights* don't fit, not the context), but it materially helped once context
size was also right-sized to the workload.

**`qwen3:8b` (8.19B dense) — the win.** With `num-ctx=4096`, loads at 5.1GB,
100% GPU, no CPU spillover. Three clean runs: **90.1% / 92.3% / 93.4%**
(91.9% average) — a full ~16 points above `qwen2.5:7b`'s ~75.5% average
across 4 runs, with mismatch counts of just 1 per run vs. `qwen2.5:7b`'s
historical systematic misses. Tradeoff: ~10.6 minutes per 91-complaint run
vs. `qwen2.5:7b`'s ~2.9 minutes — roughly 3.6x slower, consistent with it
being a "thinking"/reasoning-style model doing more work per classification
call.

**`qwen3.5:9b` (newer-gen dense) — still fails, different failure mode.**
Fits VRAM fine (5.5-6.6GB), but `chatModel.chat()` returned `null` on every
attempt — `LlmGrievanceClassifier.parse()` NPEs at `RESULT_PATTERN
.matcher(response)` because `response` itself is null. Root cause: this
model's reasoning-chain length is unpredictable per complaint, and it
sometimes exhausts the entire context budget thinking before ever emitting
the `RESULT:` line. Failed 3/3 at `num-ctx=4096` (~47-49s each — fails
almost immediately) and 1/1 at `num-ctx=8192` (7 minutes — gets further
before still failing the same way). `num-ctx=16384` was confirmed via a
direct footprint check to force the model back into a 12%/88% CPU/GPU split
before a full run was attempted — reintroducing the exact problem the
context fix was solving, for a model that still wasn't guaranteed to finish.
No context size threads "enough headroom for unpredictable thinking" and
"stays GPU-resident on 8GB" at the same time on this hardware. Abandoned
rather than chase a third context size.

**Current state:** `llm.provider=ollama`, `ollama.chat-model=qwen2.5:7b`
(left as the default by explicit choice — faster iteration loop for ongoing
dev work), `ollama.num-ctx=4096` kept permanently. **Correction, found
later (see "MCP tool wiring" below): the Ollama server's `q4_0` KV-cache
quantization was NOT a safe no-downside setting** — it corrupts
`qwen2.5:7b`'s output into complete gibberish (confirmed via direct A/B
`curl` against the same prompt: coherent, correct JSON with `q4_0` disabled;
token soup with it enabled). The `qwen3:8b`/`qwen3.5:9b` numbers above were
measured while `q4_0` was active and produced coherent, sensible-looking
output throughout (no gibberish observed in any mismatch), so they're
probably still representative — but they haven't been independently
re-verified under the corrected default-KV-cache setting. `q4_0` is off by
default again (Ollama server now runs with no `OLLAMA_KV_CACHE_TYPE`
override). `qwen3:8b` is still a confirmed, ready-to-flip upgrade path (one
`chat-model` line) whenever the accuracy gain is worth the ~3.6x slower
per-call latency; `qwen3:30b-a3b` and `qwen3.5:9b` are not viable on this
GPU for this workload regardless.

## MCP tool wiring: `commit()` converges on `GrievanceMcpTools`; citizen chat gets real LLM tool-calling

Closes the milestone-3 scoping note ("the MCP client side... is deliberately
not built yet — that belongs to milestone 4, where an actual agent exists to
consume them") and the corresponding open-items bullet below. AIGRE has run
its own MCP **server** since milestone 3 but never built the MCP **client**
side until now.

**Investigated and corrected mid-plan:** the original framing ("give the
classifier tool access during `classify()`") didn't survive scrutiny —
`classify()` runs on a brand-new grievance with no history, so none of the 3
read-only tools (`get_grievance_status`/`check_sla_status`/
`find_duplicate_chain`) would return anything useful there. Found a better
fit instead: the citizen-facing chat endpoint (`ChatController`, previously
RAG-only) had no way to answer "what's the status of my complaint?" — a
live, per-citizen question the static policy corpus can't contain. Citizens
already get their grievance UUID back at submission
(`GrievanceIntakeResponse.id`), so referencing it in a chat question is a
real, not-invented trigger.

**Part A — `commit()` converges onto `GrievanceMcpTools.updateGrievanceStatus()`**
as a plain Java method call (constructor DI, no MCP wire protocol — there's
no judgment call in finalizing already-decided values). Only the status
transition + `status_history` write converge; the 9 classification/
scheduling columns (`department_predicted`, `category`, `priority`,
`sla_due_at`, `duplicate_of_id`, etc.) stay direct JDBC in `commit()` — no
MCP tool covers them and inventing one just to avoid direct JDBC wasn't
worth it. **Deliberate behavior change, not a bug:** `updateGrievanceStatus`
sets `resolved_at` for any terminal status per its own `TERMINAL_STATUSES`
set, including `NOT_ACTIONABLE` — the old inline `UPDATE` never did. A
terminal state having a resolution timestamp is more correct than leaving it
null; confirmed inert for `GrievanceTrendsService`'s breach math (reads
`sla_due_at`, which stays NULL for `NOT_ACTIONABLE` either way).

**Part B — citizen chat gets real MCP tool-calling.** Added
`langchain4j-mcp` (resolves via `langchain4j-bom` to `1.18.0-beta28`, not
`1.18.0` — MCP support stays beta-versioned inside the otherwise-GA BOM,
worth remembering next time this comes up). `ChatController` now goes
through an `AiServices`-backed `CitizenChatAssistant` (`TokenStream`
return type) instead of calling `StreamingChatModel` directly — `TokenStream`
streams token-by-token both before and after any tool-call round trip, so
one `SseTokenStreamBridge` handles the RAG-only and tool-calling cases
uniformly (`onToolExecuted` just never fires when no tool is called).
`McpToolProvider.filterToolNames("get_grievance_status", "check_sla_status",
"find_duplicate_chain")` is the load-bearing safety mechanism — without it,
the citizen chat's LLM would also see the 2 write tools
(`update_grievance_status`/`reopen_grievance`) with no server-side ACL
stopping a confused or adversarial prompt from trying to invoke them.

**A real gap found during live verification, not just a hypothetical from
the plan:** the CLAUDE.md-documented "build the MCP client eagerly inside
the bean, catch, fall back to a no-op `ToolProvider`" pattern is correct for
a genuinely-external, possibly-down agency server — but for this
self-referential same-JVM case, it deterministically lost the race against
this app's own Netty listener on every cold start (confirmed twice in a
row, not an occasional flake): `McpClientConfig`'s bean was constructed
before `McpServerAutoConfiguration`'s routes were actually live, so eager-
connect-with-catch would have silently and *permanently* disabled
tool-calling every time the app started fresh. Fixed with
`LazyGrievanceToolProvider` — connects on first real invocation instead of
at bean-construction time (by which point the app is guaranteed fully up),
caching only a *successful* connection so a transient first-request failure
still retries on the next request rather than sticking on RAG-only forever.

**A second, unrelated, more serious bug found while debugging a test
failure this same session:** `GrievanceWorkflowServiceTest` started failing
reproducibly (3/3 identical failures) with a clearly-actionable pothole
complaint scored `NOT_ACTIONABLE`. Traced via direct `curl` A/B testing
(same prompt, same model, only the Ollama server's KV-cache setting
changed) to the `q4_0` KV-cache quantization enabled in the "Local model
comparison" work above — it produces complete gibberish output for
`qwen2.5:7b` (see the correction in that section). Not a code bug in this
feature at all, but discovered because this feature's test suite happened
to exercise a live classification call. Fixed by reverting the Ollama
server to default (non-quantized) KV cache; all 8 workflow tests pass
cleanly afterward.

**Verified live, not just via `mvn test`:** RAG-only chat (a known
eval-corpus policy question) streams an unchanged, correctly-cited answer
with zero tool calls. Submitting a real grievance via `POST /grievances`,
then asking chat "what's the status of grievance `<id>`?" returns live DB
data (submission timestamp, category, no-contact-info flag) that cannot
have come from the static RAG corpus — confirmed via
`aigre_chat_tool_calls_total{tool="get_grievance_status"} 1.0` on the
Prometheus endpoint, exactly one call to exactly the right tool.

**Automated afterward as `ChatControllerTest`** (see `docs/TEST_SCENARIOS.md`)
— and writing it caught a third real bug the manual `curl` verification
missed: the self-referential MCP URL was resolved via `@Value("${mcp.client.
grievance-url}")` at bean-construction time, which correctly picked up
`application.yml`'s fixed `server.port: 8085` for the real running app, but
`@SpringBootTest(webEnvironment = RANDOM_PORT)` binds the test server to an
actual random port — so the test's `LazyGrievanceToolProvider` was silently
connecting to the wrong port and getting zero tools back, not a wiring
failure in the feature itself. Fixed by resolving the port lazily too
(`Environment.getProperty("local.server.port", ...server.port...)`), read
at first-use time rather than injected at construction time — by which
point Spring Boot has always already published whichever port (fixed or
random) actually got bound. `mcp.client.grievance-url` as a config property
is gone; the port is now always resolved dynamically.

## Onboard a new department from a public URL of PDFs

A genuinely new city department (not just more documents for one of the
existing 6) can now be onboarded live, from a real public URL, with zero
code redeploy for department #2 onward. Two decisions shaped this: reusable
admin feature, not a one-off script; and full integration (classification +
routing), not just RAG-searchable — specifically, the classifier prompt
went **fully dynamic**, migrating all 6 existing departments' hand-tuned
prompt text into the DB rather than leaving them hardcoded and only new
ones dynamic.

**Schema**: `departments` gained a `short_name` column (the prompt's
parenthetical label, e.g. "Transportation" — not derivable from `name` by
stripping "Department of " for all 6, since DHHS/DHUD use "&", not "and").
`jurisdiction_notes` was migrated to the *exact* rich prompt text
(`schema.sql`'s old seed text was much thinner) — including DPW's
cross-department disambiguation sentences, which fixed a real documented
classification bug (hazard-adjacent DPW infrastructure incidents misrouted
to DEP). Migrating a summary instead of the verbatim text would have risked
silently reintroducing that bug.

**`DepartmentDirectory`** (new, `com.aigre.classification`): in-memory
cache of the classifier's DEPARTMENTS prompt section, built from the DB,
`@DependsOnDatabaseInitialization` so it reads `departments` only after
`schema.sql`'s seed has actually run — the first component in this codebase
doing a DB read from a constructor. Not re-queried per `classify()` call
(that's a hot-ish path); `refresh()` is called explicitly after a new
department is inserted. `LlmGrievanceClassifier` gained its first-ever DB
dependency as a result — a deliberate, known tradeoff, not a quiet one:
its own javadoc used to advertise "zero database dependency" as a feature.

**New `com.aigre.admin` package**: `POST /admin/departments` (ADMIN-only —
creating a routing target is bigger blast-radius than day-to-day supervisor
work), taking a department code/name/short-name/jurisdiction-notes plus a
`sourceUrl`. `PdfCrawlService` is this codebase's first outbound HTTP
client (`WebClient`) and first HTML-parsing dependency (`jsoup` — already a
*transitive* dependency via Tika, confirmed via `mvn dependency:tree`, so
declaring it explicitly added zero new jars). `Jsoup.parse(html, pageUrl)`'s
`absUrl("href")` resolves relative PDF links against the page's own URL —
exactly what a real index page needs, no manual `URI.resolve`.
`DepartmentOnboardingService` orchestrates: crawl → download (skip, don't
fail, on a dead link or non-PDF response) → write into
`test-data/documents/<CODE>/` → `INSERT INTO departments` → `DepartmentDirectory
.refresh()` → `CorpusIngestionService.reset()` (full wipe-and-reseed of the
*entire* vector store, in-process — not via `POST /ingest/reset`, which
turned out to be fully unauthenticated already, a pre-existing gap this
feature deliberately doesn't depend on). New `GET /departments` (public —
`department-name.pipe.ts` renders on the unauthenticated citizen status
page) returns just `id`/`name`, deliberately excluding the internal
prompt-engineering columns.

**Three real bugs found while building and verifying this, not just
theorized in the plan:**
1. **`WebClient.Builder` isn't auto-configured in this app.** Assumed it
   would be (this is a `spring-boot-starter-webflux` app), but
   `PdfCrawlService`'s constructor injection failed with
   `NoSuchBeanDefinitionException` — confirmed by actually running
   `LlmGrievanceClassifierTest` and reading the real `Caused by` chain, not
   assumed. Fixed with an explicit `WebClient.Builder` bean
   (`com.aigre.config.WebClientConfig`).
2. **The pom.xml `--` XML-comment pitfall bit again** (same one from the
   MCP-wiring session) — a comment containing a bare `--` in the middle
   fails Maven's POM parser outright. Worth adding to `CLAUDE.md`'s gotcha
   table if it comes up a third time.
3. **A boundedElastic worker thread's interrupted status leaks across
   sequential blocking calls within the same request — and this one
   actually broke production, not just this one request.** An aggressively
   short client-side `curl --max-time` cancelled an onboarding request
   mid-flight while `DepartmentOnboardingService` was still sequentially
   downloading ~60 PDF links; Reactor propagated the cancellation as a
   thread interrupt, and every *subsequent* blocking call on that same
   worker thread failed near-instantly (visible as a rapid-fire cascade of
   `Skipping ... -- java.lang.InterruptedException` log lines during the
   downloads) — but the `onboard()` call still ran to completion regardless,
   because `PdfCrawlService.download()` catches per-link and never rethrows.
   The real damage: that same still-interrupted thread then went on to call
   `corpusIngestionService.reset()`, whose very first line is
   `embeddingStore.removeAll()` — a full wipe of the *entire* RAG vector
   store, all departments, not just the new one. The lingering interrupt
   status broke the first embedding batch's blocking Ollama HTTP call
   immediately, aborting `reset()` **after** the wipe but **before**
   anything was re-added. Found for real, not hypothetically: a genuine
   citizen-chat question ("what does OEMS do?", expecting an answer from a
   newly-onboarded department's PDF) came back with a generic non-answer;
   `rag_documents` was confirmed at 0 rows. **Fixed** with
   `Thread.interrupted()` (reads and clears the flag) called explicitly
   right before `corpusIngestionService.reset()`, guaranteeing it always
   runs on a clean thread regardless of what happened earlier in the same
   request. Corpus restored via the normal `POST /ingest/reset?confirm=true`
   recovery path (116 documents — the original 108 plus PRD's 8); the OEMS
   question then answered correctly, grounded in the right PDF, sources
   cited.

**Verified live, not just via `mvn test`:** `mvn test -Dtest
=LlmGrievanceClassifierTest` (4/4 pass) confirms the dynamic prompt still
classifies correctly on fast structural cases. `ComplaintEvalHarnessTest`
run twice — 83.5% and 79.1%, both solidly inside the already-documented
Ollama variance band (65.9%–86.8%) — confirms no classification regression
from migrating the original 6 departments to DB-driven prompt text. Live
onboarding against a real public URL (LA's Department of Public Works
management-manual page, 60+ real PDF links, mostly relative URLs) produced
a working department end to end: `GET /departments` listed it, a
hand-crafted playground/park-maintenance complaint classified into it at
0.85 confidence with a sensible category, and — after finding and fixing
the corpus-wipe bug above — a real citizen question against one of the
onboarded PDFs answered correctly with proper citations. The whole
pipeline, including the classifier's live prompt update and RAG retrieval,
working for a department that didn't exist when the app booted.

**Explicitly out of scope, left manual**: employee/staff provisioning
(`department_employees` rows). A freshly onboarded department has zero
staff until someone adds them separately — a grievance routed there has no
one to claim it in the dashboard until then.

### Follow-up found by actually using the new department: irrelevant citations

Asking the citizen chat "what does OEMS do?" (answerable from the newly
onboarded PD003.pdf) got a correct answer, but with 2-3 clearly-unrelated
documents cited alongside the right one. Root cause, confirmed in
`RetrievalService.retrieve()`: it always returned exactly `rerank-to` (5)
results unconditionally, with no relevance floor — when fewer than 5
candidates were genuinely relevant, it padded the response with whatever
scored lowest rather than fewer, better citations.

**Fix shipped**: filter to `rerankScore > 0` before capping at `rerank-to`
— a rerank score of 0 is the LLM's own "not relevant" signal on its 0-10
scale, per the rerank prompt itself.

**Verified insufficient on its own, not just theorized**: re-running the
same question 4 times showed irrelevant documents (`trend-analysis-policy
.txt`, `fleet-equipment-policy.txt`, `citizen-notification-policy.txt`)
routinely scoring 2-5 — never landing on the literal 0 the filter catches,
and twice actually *tying or beating* the genuinely relevant `PD003.pdf`
chunks (which themselves scored as low as 2.0 in the same runs). A hard
numeric threshold can't cleanly separate the two classes here: raising the
bar would cut relevant and irrelevant content roughly equally, since they
occupy the same score range with this model. This is the same underlying
limitation `RetrievalService`'s own javadoc already documents — the
cross-encoder ONNX reranker attempted as "the theoretically better fix" is
blocked by a Windows DLL version conflict, reverted to LLM-based rerank as
a known-imperfect fallback.

**Decision**: keep the `>0` filter (real, low-risk improvement, catches the
literal-zero case that caused the original incident) and stop there for
now — a stricter threshold or the cross-encoder revisit are both real
options with real costs, not pursued this round. **Verified no regression**:
`RagEvalSuiteTest` re-run at 27/34 — within the already-documented variance
band for this exact suite (26/34-29/34 across prior runs, see the
provider-comparison history above) — and the top-1 assertions this test
checks are structurally unaffected by tail-filtering anyway (the filter
only removes low-scored entries from the bottom of an already-sorted list,
never changes which result ranks first).

## Open items to revisit
- Dark mode — **built, see "Dark mode" below**. The "fast follow, not a
  rewrite" prediction from the redesign pass held up: verified by direct
  `sass` compilation that Angular Material's `mat.theme()` was already
  emitting every `--mat-sys-*` token wrapped in the CSS `light-dark()`
  function, dormant behind one hardcoded `color-scheme: light;` line.
- Business-hours-aware SLA calendar vs. flat calendar days — currently flat
  calendar-hour arithmetic in `SlaCalculator`; revisit before this becomes
  the source of truth for real SLA breach reporting.
- Attachment/photo evidence (e.g. pothole photos) — out of scope for
  text-only NLP in this pass.
- Duplicate detection (scenario 5) and reopened-case handling (scenario 7) —
  **built, see below** (was previously an open item: `find_duplicate_chain`
  could walk a chain once a `duplicate_of_id` link existed, but nothing
  created that link, and `update_grievance_status` didn't apply the
  priority-bump-on-reopen rule).
- Postgres-backed LangGraph4j checkpointing — **built, see "Postgres-backed
  LangGraph4j checkpointing: paused reviews now survive a restart" above**
  (was previously an open item: milestone 4's in-memory `MemorySaver` lost a
  paused review's checkpoint on every app restart).
- Real auth/RBAC for the employee dashboard — **built, see below**. Residual
  demo-grade gaps: all 12 seeded accounts share one password, the JWT
  signing secret is a fixed `application.yml` value rather than a secrets
  manager, no refresh-token flow (8-hour token, then re-login).
- Frontend `API_BASE` hardcoded `http://localhost:8085` — **fixed, see
  "Single-origin static hosting + Cloudflare Tunnel" below**: both
  `api.service.ts` and `auth.service.ts` now use a relative empty string,
  proxied in dev (`proxy.conf.json`) and same-origin when the backend serves
  the built frontend directly.
- A paused (human-review) grievance was invisible in every department's
  queue until it committed — **fixed, see "Paused-review visibility bug:
  fixed" below**.
- The milestone-4 workflow's own MCP-tool consumption — **built, see "MCP
  tool wiring" above**. `commit()` now converges its status transition onto
  `GrievanceMcpTools.updateGrievanceStatus()` directly, and the citizen chat
  gets real LangChain4j MCP-client tool-calling for live per-grievance
  questions. `GrievanceIntakeService` (the non-graph `/grievances` path)
  still writes directly and independently — a separate, not-yet-touched
  code path if unification there is ever wanted.

## Full plan
The complete Milestone-0 plan (domain model, routing/escalation scenarios,
correctness table, test-data spec with eval questions, day-one scaffold
checklist) lives at:
`C:\Users\srika\.claude\plans\read-kickoff-md-and-new-project-instruct-serialized-goblet.md`
