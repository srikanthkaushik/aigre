# AIGRE — Test Scenarios

This document covers three distinct things: the automated backend/frontend test
suites, the 10 domain routing scenarios the system is designed against (with real
labeled examples for the DMV bureaus and for the original city build), and a manual QA
checklist for exercising the running application by hand.

**Which profile the tests run under.** AIGRE is deployed as the `dmv` profile (five NH
DMV bureaus), but most of the automated suite predates it. Apart from the four DMV
tests, every backend test runs under the **default (city) profile** against the city database
`aigre` — many hardcode city department codes (`DOT`, `DPW`, `DHHS`, `DEP`) and expect
the city seed. They exercise profile-independent machinery (workflow, duplicates, SLA,
auth, MCP, caching, redaction), so they still cover the DMV deployment's code paths;
the DMV-specific prompt, bureaus, seed and corpus are covered by the DMV tests.

## Contents

- [Automated backend test suite](#automated-backend-test-suite)
- [Automated frontend test suite](#automated-frontend-test-suite)
- [The 10 domain routing scenarios](#the-10-domain-routing-scenarios)
- [Manual QA checklist](#manual-qa-checklist)

---

## Automated backend test suite

Run everything:

```
mvn test
```

Run a single class:

```
mvn test -Dtest=ClassName
```

All rows below are city-profile tests except the three `Dmv*` classes (DMV only) and
`ComplaintEvalHarnessTest` (either, by file — see below).

| Test class | Type | What it verifies |
|---|---|---|
| `SlaCalculatorTest` | Pure unit | SLA due-date arithmetic — no LLM, no DB, deterministic |
| `GrievanceMcpToolsTest` | Integration (real Postgres) | All 4 MCP tools against 5 deliberately-seeded edge cases (bad department code, stale unescalated breach, 2-hop duplicate chain, anonymous no-contact citizen, never-classified row) plus not-found/malformed-ID error paths |
| `GrievanceWorkflowServiceTest` | Integration (real Postgres + live Ollama) | The auto-commit (high-confidence) path through the LangGraph4j workflow, end to end |
| `GrievanceWorkflowPauseResumeTest` | Integration (real Postgres, **mocked** classifier) | The interrupt/resume *mechanics* deterministically — pause on low confidence, resume with a supervisor decision, skip-review on not-actionable, and the citizen-clarification path (`clarify()`) both when it resolves the pause and when it doesn't. Mocked deliberately so this test isn't flaky for reasons unrelated to graph wiring (see note below) |
| `GrievanceTrendsServiceTest` | Integration (real Postgres) | The 5 trend aggregation queries against isolated fixture data (a dedicated `ZZTEST` department code, cleaned up after) — exact expected counts/averages |
| `LlmGrievanceClassifierTest` | Integration (live Ollama) | Fast structural smoke test on the city prompt — a handful of unambiguous cases (confident pothole → `DOT`, critical gas leak, vague/unconfident, non-actionable compliment). Not the accuracy measurement |
| `ComplaintEvalHarnessTest` | Integration (live Ollama, slow) | **The accuracy measurement** — every labeled complaint in an eval file run through the real classification pipeline, department-match accuracy reported. Defaults to the city's 91 (`test-data/grievances/eval-complaints.jsonl`); the DMV's 42 with the command below. See variance note below |
| `RetrievalEvalTest` | Integration (live Ollama) | Day-one retrieval smoke test — ⚠️ **destructive**, see warning below |
| `RagEvalSuiteTest` | Integration (live Ollama, slow, ~9 min) | 34 labeled retrieval cases from `test-data/eval-questions.md` against the city policy corpus, through the real hybrid-retrieval + rerank pipeline |
| `ChatControllerTest` | Integration (real Postgres + live Ollama + real self-referential MCP client) | Citizen chat's MCP tool-calling, end to end: a deterministic check that only the 3 read-only tools are ever exposed to the model (`filterToolNames`, no LLM involved), a RAG-only question that must never trigger a tool call, and a status question about a just-submitted real grievance that must trigger exactly one live `get_grievance_status` call. See variance note below |
| `ClassifierPromptTemplateTest` | Pure unit | Pins `prompts/city/classifier.txt` to the exact text its accuracy was measured with (SHA-256), and checks every profile's prompt has its two `%s` slots |
| `DmvRagEvalSuiteTest` | Integration (live Ollama, `dmv` profile only) | 16 retrieval questions against the NH DMV rule corpus, each expecting a specific Saf-C chapter as the top result. Skipped unless run with `-Dspring.profiles.active=dmv` against the DMV database (`RUNNING.md` "Running the DMV profile") |
| `DmvGrievanceClassifierTest` | Integration (live Ollama, `dmv` profile only) | The DMV counterpart to `LlmGrievanceClassifierTest`: lost title → `TITLE` (confident), paid-but-still-suspended → `FR` at `HIGH` (the DL/FR boundary), a car registered in the citizen's name → `CRITICAL`, a vague complaint → not confident, a pothole → not actionable |
| `DmvSecurityIntegrationTest` | Integration (real Postgres, seeded DMV employees, `dmv` profile only) | The DMV counterpart to `SecurityIntegrationTest`: `/app-config` serves "Bureau" wording, `/departments` is exactly the five bureaus, a bureau login carries its bureau, a bureau queue holds only that bureau's rows, AGENT can't change status, SUPERVISOR can within its bureau but not across, ADMIN can act on any bureau. Needs `test-data/dmv/sql/seed.sql` loaded |
| `PriorityTest` | Pure unit | Priority tier bumping (one tier up, `CRITICAL` stays `CRITICAL`), used by the reopen rule |
| `JwtServiceTest` | Pure unit | Employee JWT round-trip, malformed token, wrong signing key and expired token all handled |
| `ChatResponseCacheTest` | Pure unit | The `/chat/stream` exact-match cache: hit/miss, case and whitespace normalisation, per-department keys, TTL expiry, disabled mode, `invalidateAll()` |
| `PiiRedactorTest` | Pure unit | PII redaction against the 4 PII-laced labeled complaints (GRV-074..077) |
| `PiiRedactionWebFilterTest` | Integration (real Postgres, **mocked** classifier) | The redaction filter actually runs on the HTTP path and rewrites the body before it reaches the controller and database |
| `SecurityIntegrationTest` | Integration (real Postgres, seeded employees) | Login, department scoping and role gating (AGENT vs SUPERVISOR vs anonymous) on the real endpoints. Needs `seed.sql` loaded |
| `DuplicateDetectionServiceTest` | Integration (real Postgres) | Duplicate matching rules: same department and category, lookback window, terminal/duplicate statuses excluded, self excluded, earliest candidate wins. Uses its own throwaway fixture rows |
| `GrievanceIntakeDuplicateTest` | Integration (real Postgres, **mocked** classifier) | Duplicate linking through the plain `POST /grievances` intake path |
| `GrievanceWorkflowDuplicateTest` | Integration (real Postgres, **mocked** classifier) | The same duplicate linking through the LangGraph4j workflow's `commit()` node. The two commit paths don't share code, so both are covered |
| `EmailGrievancePollerTest` | Integration (real Postgres, embedded GreenMail mailbox, **mocked** classifier) | The email channel end to end: an unread message becomes a grievance, and the SEEN flag prevents reprocessing |
| `CorpusIngestionServiceCacheInvalidationTest` | Integration (live Ollama, slow) | `POST /ingest/reset` clears the chat response cache. **Re-ingests the whole corpus**, so run it deliberately: `mvn test -Dtest=CorpusIngestionServiceCacheInvalidationTest` |

**The DMV tests** run under the `dmv` profile against `aigre_dmv` with the DMV seed and
corpus loaded (`RUNNING.md`, "Running the DMV profile"). The smoke and security tests are
quick (about a minute and a half, mostly the five live classifications):

```
mvn test -Dtest=DmvGrievanceClassifierTest,DmvSecurityIntegrationTest -Dspring.profiles.active=dmv -Dspring.datasource.url=jdbc:postgresql://localhost:5434/aigre_dmv
```

The evals take minutes. Last measured: classification 40/42 (95.2%), retrieval 12/16
top-1 (`PROJECT.md`, "Domain profiles"):

```
mvn test -Dtest=ComplaintEvalHarnessTest,DmvRagEvalSuiteTest -Dspring.profiles.active=dmv -Dspring.datasource.url=jdbc:postgresql://localhost:5434/aigre_dmv -Daigre.eval.complaints=test-data/dmv/grievances/eval-complaints.jsonl
```

**Everything else** expects the city dev stack from `RUNNING.md` steps 1–4: `aigre-pg` on
port 5434 with the city `test-data/sql/seed.sql` loaded into `aigre`, and Ollama on 11434
for the "live Ollama" rows. Run plain `mvn test` with `SPRING_PROFILES_ACTIVE` and
`SPRING_DATASOURCE_URL` unset — if they're still set from running the DMV profile, the
city tests will fail against the DMV database. Tests marked **mocked** classifier don't
need Ollama.

### ⚠️ `RetrievalEvalTest` is destructive against the shared dev database

Its `@BeforeEach` calls `embeddingStore.removeAll()` and reseeds just 2 hardcoded
fixture rows. Running it wipes whatever real corpus was loaded via `POST
/ingest/reset`. **Re-run corpus ingestion afterward** if you need the full corpus
back:

```
curl -X POST -H "Authorization: Bearer <ADMIN_TOKEN>" "http://localhost:8085/ingest/reset?confirm=true"
```

This bit the running application for real during development — chat citations
started returning "unknown source" after this test ran as part of an unrelated
sanity check. Documented in `PROJECT.md` and in a warning javadoc on the test class
itself.

### LLM sampling variance is expected, not a bug

`ComplaintEvalHarnessTest` against Ollama's `qwen2.5:7b` has measured 65.9%–86.8%
accuracy across different runs of the *identical* 91 city cases and code — genuine model
sampling variance, confirmed by re-running a single flagged case in isolation and
getting a different (correct) answer with no code change in between. The regression
floor is set at 55% (a comfortable margin below the observed low end), not pinned to
any single run's number. Anthropic (`claude-sonnet-5`) scored a much more stable
95.6% in a head-to-head comparison — see `ARCHITECTURE.md` and `PROJECT.md` for the
full writeup. **If this test fails once, re-run it before assuming a regression.**

The same caveat applies to `ChatControllerTest.statusQuestionAboutARealGrievance
TriggersALiveToolCall` — whether the model reliably recognizes a grievance ID
(a `G0001`-style format) in a question as a cue to call `get_grievance_status`
is live model behavior, not deterministic. If it fails, first check `onlyReadOnlyToolsAreEverExposedToThe
CitizenChatModel` still passes (that one *is* deterministic, no LLM involved) —
if that one fails too, it's a real wiring regression, not variance.

---

## Automated frontend test suite

```
cd frontend
npm test
```

Currently one smoke test (`app.spec.ts` — the root component instantiates). The
frontend's real verification story in this project has been build-time (`ng build`
must be clean — template/type errors are caught there) plus live `curl`/data
verification of every backend contract the frontend depends on, **not** component
unit tests or browser automation — there is no headless-browser tooling wired into
this project yet. See the [Manual QA checklist](#manual-qa-checklist) below for how
the UI itself is actually verified.

---

## The 10 domain routing scenarios

These are the routing/escalation branches the classification and workflow pipeline is
designed against (originally defined in the Milestone-0 plan, §1.4). Each profile has
its own labeled examples with the same scenario numbering, and `ComplaintEvalHarnessTest`
asserts against whichever file it's given. One representative example per scenario.

### DMV bureaus (the deployed `dmv` profile)

Labeled examples in `test-data/dmv/grievances/eval-complaints.jsonl`. Bureaus: `REG`
Registration, `DL` Driver Licensing, `FR` Financial Responsibility, `TITLE` Title &
Anti-Theft, `DEALER` Dealers & Inspections.

| # | Scenario | Example | Expected outcome |
|---|---|---|---|
| 1 | Clear single-bureau routing | *"I lost my car title when I moved last year and now I need it to sell the car. How do I get a replacement?"* | `TITLE`, `duplicate-title`, `MEDIUM`, `TRIAGED` |
| 2 | Multi-bureau ambiguity | *"I bought a car from my neighbor and when I went to register it, the clerk said the title was never transferred out of his late wife's name."* | Spans `TITLE`/`REG` (`TITLE` preferred — the problem is the title, it only surfaced at registration), `title-transfer`, `MEDIUM`, `TRIAGED` |
| 3 | Fraud / theft escalation | *"Someone transferred the title of my truck to themselves using a forged signature. The truck is gone from my driveway."* | `TITLE`, `CRITICAL` (active fraud and vehicle theft force the tier) |
| 4 | Low-confidence / needs clarification | *"The DMV messed up my paperwork again."* | Actionable, but no bureau/category/priority forced; `NEEDS_CLARIFICATION` (plain intake) or pauses at `human_review` (workflow path) |
| 5 | Duplicate detection | *(not in the DMV eval file)* | Same rule as the city build: a new complaint matching an open one in the same bureau and category within 7 days links via `duplicate_of_id` with status `DUPLICATE` — seen live in `APP_WALKTHROUGH.md` |
| 6 | SLA breach imminent/breached | *(state-based; the DMV seed has no deliberately breached row)* | Open cases past `sla_due_at` are flagged `BREACHED` in the bureau queue and counted in the Trends SLA snapshot |
| 7 | Reopened complaint | *(citizen action, not intake text)* | `REOPENED`, priority bumped one tier, fresh SLA — the lien-release reopen in `APP_WALKTHROUGH.md` (`MEDIUM` → `HIGH`) |
| 8 | Sentiment must not bump priority | *"My registration renewal has been pending for a month and honestly I'm furious, this is ridiculous and incompetent!"* | `REG`, `registration-renewal`, `MEDIUM`, `NEGATIVE` — anger alone never changes the tier |
| 9 | Out-of-scope / non-actionable | *"There's a huge pothole on Route 3 that blew out my tire."* | `actionable=false`, `NOT_ACTIONABLE` — road maintenance isn't the DMV's; same for compliments, private-seller disputes and traffic-ticket fines |
| 10 | Manual re-route candidate | *"This is a registration problem: my lender still shows on my title after I paid off the loan."* (citizen mislabels it) | `TITLE`, `lien-release` — a lien on the title is `TITLE`'s regardless of the citizen's wording |

Distribution: scenario 1 ×24, 2 ×4, 3 ×3, 4 ×3, 8 ×2, 9 ×4, 10 ×2 (42 total; 5–7 depend
on existing data or a citizen action, so they aren't in the file).

### City build (default profile)

Labeled examples in `test-data/grievances/eval-complaints.jsonl`. Departments: `DOT`
Transportation, `DPW` Public Works, `DHHS` Health & Human Services, `DOE` Education,
`DHUD` Housing & Urban Development, `DEP` Environmental Protection.

| # | Scenario | Example | Expected outcome |
|---|---|---|---|
| 1 | Clear single-department routing | *"There's a large pothole on Main Street near the bakery that's been there for two weeks and is damaging cars."* | `DOT`, `road-surface`, `MEDIUM`, `TRIAGED` |
| 2 | Multi-department ambiguity | *"The playground fence at Lincoln Elementary is broken and kids are running right out into the street during recess."* | Spans `DOE`/`DOT`/`DPW`, `CRITICAL` (child-safety hazard forces the tier regardless of department split), `TRIAGED` |
| 3 | Safety-critical escalation | *"I smell gas near the manhole cover on Elm Street, it's pretty strong."* | `DPW`, `CRITICAL` (hazard keyword forces the tier immediately) |
| 4 | Low-confidence / needs clarification | *"Things have been bad on my street lately and nobody seems to care."* | No department/category/priority forced; `NEEDS_CLARIFICATION` (plain intake) or pauses at `human_review` (workflow path) |
| 5 | Duplicate detection | *"There's a pothole at the corner of 5th and Birch that's about 3 inches deep and causing cars to swerve."* | First of a 3-complaint duplicate cluster (see `GRV-042`/`GRV-043`) — later ones link via `duplicate_of_id` (`DuplicateDetectionService`) |
| 6 | SLA breach imminent/breached | *(state-based, not intake-based — lives in `test-data/sql/seed.sql`, not the complaint eval set)* | A seeded `IN_PROGRESS` row with `sla_due_at` in the past — exercised by `GrievanceMcpToolsTest.staleUnescalatedBreachIsDetected` and the Trends tab's SLA snapshot |
| 7 | Reopened complaint | *"I'm reopening my case — the pothole on Main St that was marked resolved is already back and just as bad as before."* | `REOPENED`, priority bumped one tier from the original `MEDIUM` to `HIGH` |
| 8 | Sentiment-driven priority bump | *"There's a pothole outside my office on Maple St, medium size, hasn't caused any damage that I've seen."* (calm half of a paired case) | `MEDIUM`, `NEUTRAL` — its angry-tone pair (`GRV-054`) should land at the **same** priority; only anger *plus* a repeat submission bumps a tier |
| 9 | Out-of-scope / non-actionable | *"Great job on the new bike lane downtown, it's been really nice to use this month."* | `actionable=false`, `NOT_ACTIONABLE`, no department forced |
| 10 | Manual re-route candidate | *"There's a big pothole on Maple Ave near the school that's actually a DPW road surface issue."* (citizen mislabels it) | `DOT` (road-surface potholes are DOT's jurisdiction regardless of the citizen's own wording) — worded to plausibly fool a classifier, exercising why manual employee re-routing needs to exist |

Full distribution: scenario 1 ×42, 2 ×5, 3 ×5, 4 ×6, 5 ×8, 7 ×4, 8 ×11, 9 ×6, 10 ×4
(91 total; scenario 6 is intentionally excluded from this file since it's a
state/time-based condition, not something a citizen types).

---

## Manual QA checklist

There's no browser automation checked into this project (screenshots and live checks
use a throwaway Playwright script outside the repo), so use this checklist to exercise
the running app by hand after any frontend change. Requires both servers up
(`RUNNING.md`, "Running the DMV profile") and the corpus/seed data loaded. The
examples are from the DMV table above; on the city build use the city table's.

**Citizen portal:**

- [ ] Every label says "Bureau" (not "Department"), and the landing page names the NH
      Division of Motor Vehicles.
- [ ] Submit a clear complaint (try the scenario 1 example above) → routes
      immediately, shows bureau/category/priority/SLA date.
- [ ] Submit a vague complaint (scenario 4 example) → shows "needs a closer look",
      *not* a forced bureau, with an inline "Can you tell us more?" form.
- [ ] Add real detail in that form → reclassifies the combined text and, if now
      confident, auto-routes without a supervisor (banner flips to the normal "Routed
      to ..." state). The form does not reappear.
- [ ] Add vague/unhelpful detail instead → stays in "needs a closer look" state with
      an updated message ("this still needs a supervisor's review"); the form is gone.
- [ ] Submit the pothole (scenario 9 example) or a pure compliment → "not something
      this portal handles".
- [ ] Click **Track this** on a fresh submission → jumps to Check Status with the ID
      pre-filled and resolves correctly.
- [ ] Check status with an unknown/malformed ID → clear error, not a raw stack trace.
- [ ] Ask a policy question (try "How do I get a duplicate title if mine was lost?") →
      streams token-by-token, ends with a real Saf-C citation (Saf-C 1900).
- [ ] Ask a question with no corpus coverage → the model says it doesn't know rather
      than fabricating an answer.
- [ ] Click an example-question chip in the empty chat state → sends it immediately.

**Employee dashboard:**

- [ ] Sign in as a bureau employee (e.g. `priscilla.moreau`) → the queue and Trends
      are scoped to that bureau; long bureau names ("Bureau of Certificate of Title and
      Anti-Theft") don't break the toolbar or tabs.
- [ ] Sign in as `ops.admin` → the bureau filter shows "All Bureaus" plus the five
      bureaus; vague complaints with no bureau appear in its Pending Review queue.
- [ ] Open **Review** on a Pending Review item → shows the citizen's original text,
      the LLM's reasoning, and an editable form with "(keep as-is)" defaults.
- [ ] Submit a review decision → item leaves the Pending Review queue, appears in the
      correct bureau's queue with `department_confirmed` reflecting the decision.
- [ ] Open **View** on an already-routed item → read-only detail, no edit form.
- [ ] Confirm SLA-breached rows are visually flagged in the bureau queue table.
- [ ] Switch pagination page size / page on both tables.
- [ ] Trends tab: toggle own bureau ↔ All Bureaus and 7/30/90-day window →
      all 4 charts and the SLA snapshot update together.
- [ ] Resize the browser to a narrow width → toolbar nav collapses into a menu; stat
      row and chart grid reflow to a single column rather than overflowing.

**Cross-cutting:**

- [ ] Click the AIGRE mark/title in the toolbar from any page → returns to `/`.
- [ ] Force a backend error (e.g. stop the backend, submit a form) → the frontend
      shows a real error message, not a silent failure or an unhandled exception in
      the console.
