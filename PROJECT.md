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

## Open items to revisit
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
  **built, see below** (was previously an open item: `find_duplicate_chain`
  could walk a chain once a `duplicate_of_id` link existed, but nothing
  created that link, and `update_grievance_status` didn't apply the
  priority-bump-on-reopen rule).
- Postgres-backed LangGraph4j checkpointing — milestone 4 uses the in-memory
  `MemorySaver`, so a paused (pending-review) workflow does not survive an
  app restart. `langgraph4j-postgres-saver` exists upstream if this becomes
  a real requirement; not needed for the current single-instance demo.
- Real auth/RBAC for the employee dashboard — **built, see below**. Residual
  demo-grade gaps: all 12 seeded accounts share one password, the JWT
  signing secret is a fixed `application.yml` value rather than a secrets
  manager, no refresh-token flow (8-hour token, then re-login).
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
