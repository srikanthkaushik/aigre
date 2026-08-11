# AIGRE — Test Scenarios

This document covers three distinct things: the automated backend/frontend test
suites, the 10 domain routing scenarios the system is designed against (with real
labeled examples), and a manual QA checklist for exercising the running application
by hand.

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

| Test class | Type | What it verifies |
|---|---|---|
| `SlaCalculatorTest` | Pure unit | SLA due-date arithmetic — no LLM, no DB, deterministic |
| `GrievanceMcpToolsTest` | Integration (real Postgres) | All 4 MCP tools against 5 deliberately-seeded edge cases (bad department code, stale unescalated breach, 2-hop duplicate chain, anonymous no-contact citizen, never-classified row) plus not-found/malformed-ID error paths |
| `GrievanceWorkflowServiceTest` | Integration (real Postgres + live Ollama) | The auto-commit (high-confidence) path through the LangGraph4j workflow, end to end |
| `GrievanceWorkflowPauseResumeTest` | Integration (real Postgres, **mocked** classifier) | The interrupt/resume *mechanics* deterministically — pause on low confidence, resume with a supervisor decision, skip-review on not-actionable. Mocked deliberately so this test isn't flaky for reasons unrelated to graph wiring (see note below) |
| `GrievanceTrendsServiceTest` | Integration (real Postgres) | The 5 trend aggregation queries against isolated fixture data (a dedicated `ZZTEST` department code, cleaned up after) — exact expected counts/averages |
| `LlmGrievanceClassifierTest` | Integration (live Ollama) | Fast structural smoke test — a handful of unambiguous cases (confident pothole, critical gas leak, vague/unconfident, non-actionable compliment). Not the accuracy measurement |
| `ComplaintEvalHarnessTest` | Integration (live Ollama, slow) | **The accuracy measurement** — all 91 labeled complaints in `test-data/grievances/eval-complaints.jsonl` run through the real classification pipeline, department-match accuracy reported. See variance note below |
| `RetrievalEvalTest` | Integration (live Ollama) | Day-one retrieval smoke test — ⚠️ **destructive**, see warning below |
| `RagEvalSuiteTest` | Integration (live Ollama, slow, ~9 min) | 34 labeled retrieval cases from `test-data/eval-questions.md` against the real hybrid-retrieval + rerank pipeline |

### ⚠️ `RetrievalEvalTest` is destructive against the shared dev database

Its `@BeforeEach` calls `embeddingStore.removeAll()` and reseeds just 2 hardcoded
fixture rows. Running it wipes whatever real corpus was loaded via `POST
/ingest/reset`. **Re-run corpus ingestion afterward** if you need the full corpus
back:

```
curl -X POST "http://localhost:8085/ingest/reset?confirm=true"
```

This bit the running application for real during development — chat citations
started returning "unknown source" after this test ran as part of an unrelated
sanity check. Documented in `PROJECT.md` and in a warning javadoc on the test class
itself.

### LLM sampling variance is expected, not a bug

`ComplaintEvalHarnessTest` against Ollama's `qwen2.5:7b` has measured 65.9%–86.8%
accuracy across different runs of the *identical* 91 cases and code — genuine model
sampling variance, confirmed by re-running a single flagged case in isolation and
getting a different (correct) answer with no code change in between. The regression
floor is set at 55% (a comfortable margin below the observed low end), not pinned to
any single run's number. Anthropic (`claude-sonnet-5`) scored a much more stable
95.6% in a head-to-head comparison — see `ARCHITECTURE.md` and `PROJECT.md` for the
full writeup. **If this test fails once, re-run it before assuming a regression.**

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
designed against (originally defined in the Milestone-0 plan, §1.4). Each is backed by
real labeled examples in `test-data/grievances/eval-complaints.jsonl`
(`ComplaintEvalHarnessTest` asserts against all of them); one representative example is
shown per scenario below.

| # | Scenario | Example | Expected outcome |
|---|---|---|---|
| 1 | Clear single-department routing | *"There's a large pothole on Main Street near the bakery that's been there for two weeks and is damaging cars."* | `DOT`, `road-surface`, `MEDIUM`, `TRIAGED` |
| 2 | Multi-department ambiguity | *"The playground fence at Lincoln Elementary is broken and kids are running right out into the street during recess."* | Spans `DOE`/`DOT`/`DPW`, `CRITICAL` (child-safety hazard forces the tier regardless of department split), `TRIAGED` |
| 3 | Safety-critical escalation | *"I smell gas near the manhole cover on Elm Street, it's pretty strong."* | `DPW`, `CRITICAL` (hazard keyword forces the tier immediately) |
| 4 | Low-confidence / needs clarification | *"Things have been bad on my street lately and nobody seems to care."* | No department/category/priority forced; `NEEDS_CLARIFICATION` (plain intake) or pauses at `human_review` (workflow path) |
| 5 | Duplicate detection | *"There's a pothole at the corner of 5th and Birch that's about 3 inches deep and causing cars to swerve."* | First of a 3-complaint duplicate cluster (see `GRV-042`/`GRV-043`) — later ones should link via `duplicate_of_id` *(detection itself is unbuilt — see `ARCHITECTURE.md` limitations; the labeled data exists, `find_duplicate_chain` can walk a link once it exists)* |
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

Since there's no browser automation in this project yet, use this checklist to
exercise the running app by hand after any frontend change. Requires both servers up
(`RUNNING.md`) and the corpus/seed data loaded.

**Citizen portal:**

- [ ] Submit a clear complaint (try the scenario 1 example above) → routes
      immediately, shows department/category/priority/SLA date.
- [ ] Submit a vague complaint (scenario 4 example) → shows "needs a closer look",
      *not* a forced department.
- [ ] Submit a pure compliment (scenario 9 example) → "not something this portal
      handles".
- [ ] Click **Track this** on a fresh submission → jumps to Check Status with the ID
      pre-filled and resolves correctly.
- [ ] Check status with an unknown/malformed ID → clear error, not a raw stack trace.
- [ ] Ask a policy question (try the pothole SLA example) → streams token-by-token,
      ends with a correct, real citation.
- [ ] Ask a question with no corpus coverage → the model says it doesn't know rather
      than fabricating an answer.
- [ ] Click an example-question chip in the empty chat state → sends it immediately.

**Employee dashboard:**

- [ ] Switch the "Viewing as" department picker → department queue and Trends both
      refresh to the new department; long department names don't wrap.
- [ ] Open **Review** on a Pending Review item → shows the citizen's original text,
      the LLM's reasoning, and an editable form with "(keep as-is)" defaults.
- [ ] Submit a review decision → item leaves the Pending Review queue, appears in the
      correct department's queue with `department_confirmed` reflecting the decision.
- [ ] Open **View** on an already-routed item → read-only detail, no edit form.
- [ ] Confirm SLA-breached rows are visually flagged in the department queue table.
- [ ] Switch pagination page size / page on both tables.
- [ ] Trends tab: toggle This Department ↔ All Departments and 7/30/90-day window →
      all 4 charts and the SLA snapshot update together.
- [ ] Resize the browser to a narrow width → toolbar nav collapses into a menu; stat
      row and chart grid reflow to a single column rather than overflowing.

**Cross-cutting:**

- [ ] Click the AIGRE mark/title in the toolbar from any page → returns to `/`.
- [ ] Force a backend error (e.g. stop the backend, submit a form) → the frontend
      shows a real error message, not a silent failure or an unhandled exception in
      the console.
