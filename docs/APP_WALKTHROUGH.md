# AIGRE — Application Walkthrough

A narrative tour of AIGRE from both sides of the product: the citizen submitting and
tracking a complaint, and the department employee reviewing and managing them. Every
example below is a real input/output pair verified against the running application,
not a mockup.

Frontend: `http://localhost:4300` (dev server) · Backend: `http://localhost:8085`.
See `RUNNING.md` to get both running locally.

---

## Landing page (`/`)

The entry point: a short hero ("Grievance Resolution Portal — AI-assisted intake,
classification, and routing") and two cards — **Citizen Portal** and **Employee
Dashboard**. The AIGRE mark and title in the top toolbar are clickable from anywhere in
the app and return here.

![Landing page](images/01-landing.png)

---

## Citizen Portal (`/citizen`)

Three tabs: **Submit a Complaint**, **Check Status**, **Ask a Question**.

### Submit a Complaint

A free-text box ("Describe the issue"), optional name/email fields, and a Submit
button. On the right, a "What happens next" card explains the three-step flow (AI
classifies → clear cases route immediately, ambiguous ones pause for a supervisor →
track anytime with your ID) — set up before the citizen even submits, so the possible
"needs a closer look" outcome isn't a surprise.

![Submit a Complaint form](images/02-citizen-submit-form.png)

**Example 1 — a clear, confident case:**

> *"There's a large pothole on Maple Street in front of 214 that's been there for two
> weeks and is damaging car tires."*

Result banner (green, `check_circle` icon):

> **Routed to Department of Transportation**
> Category: road-surface · Priority: MEDIUM
> Expected resolution by [date + 5 business days].

The grievance ID is shown with a **Track this** button that jumps straight to the
Check Status tab with the ID pre-filled.

![Submit result — routed to DOT](images/03-citizen-submit-result.png)

**Example 2 — a vague case that needs a human:**

> *"Things have been bad on my street lately and nobody seems to care."*

Result banner (amber, `hourglass_top` icon):

> **Needs a closer look**
> A supervisor will review your complaint before it's routed. If you can add a bit
> more detail below, we may be able to route it right away instead.

Nothing is guessed here — no department, no priority. This is the human-approval-gate
workflow described in `ARCHITECTURE.md`; the citizen sees the honest "pending" state,
and the case shows up in a supervisor's Pending Review queue on the Employee
Dashboard.

Unlike the plain pause, though, the citizen gets one inline chance to resolve it
themselves first — a small "Can you tell us more?" form appears right in the banner:

![Pending review with the inline clarification form](images/11-clarify-pending-with-form.png)

**Example — verified live:** adding *"Specifically there is a large pothole on Elm
Street that has damaged two of my tires this month."* reclassifies the **combined**
text (not just the new sentence) and, since it's now confident, auto-resumes the same
paused workflow — no supervisor needed after all:

![Result after clarification — now routed](images/12-clarify-result.png)

If the added detail still isn't enough to classify confidently, the form reappears
once more (2 attempts total, not one-shot) — classification confidence has documented
run-to-run sampling variance (see `PROJECT.md`), so a second try genuinely has a real
chance of succeeding even with the same or similar wording. Confirmed live: the exact
same clarification text failed once and then succeeded immediately on an identical
retry, no code changes in between. After 2 attempts, the case stays parked for a
supervisor with the fuller text saved either way, and the form doesn't reappear —
bounded, not an open-ended back-and-forth.

**Example 3 — out of scope:**

A pure compliment or a federal/state matter comes back as **"Not something this portal
handles"** with the model's own one-line reasoning, rather than being forced into a
department queue.

### Check Status

Paste a grievance ID, click **Check**. Returns a receipt-style card: status chip,
department, category, priority, submitted date, SLA due date, and resolution notes if
any. Works for *any* grievance ID — one submitted through the workflow, one submitted
through the older plain-intake path, or a seeded demo row — since it reads directly
from the systems-of-record table rather than depending on workflow state.

![Check Status result](images/04-citizen-status.png)

### Ask a Question

A chat interface backed by the RAG pipeline over the department policy corpus. Empty
state offers three clickable example questions. Answers stream in token-by-token and
end with citation cards (document filename + department) for whatever was actually
retrieved and used.

![Chat empty state with example questions](images/05-citizen-chat-empty.png)

**Example — verified live:**

> *"How long does DOT have to repair a reported pothole once it's submitted?"*

> DOT has 5 business days to repair a reported pothole once it's submitted, per city
> code 14-2.

Citation: `road-maintenance-sop-v2-current.txt` (DOT) — correctly the *current* SOP,
not the superseded v1 that also exists in the corpus specifically to test this.

![Chat answer with citation cards](images/06-citizen-chat-answer.png)

**Example — a broader question, multiple sources:**

> *"Give me some details about free immunization"*

> Free immunizations for children can be accessed at walk-in clinics operated by DHHS
> at three community health sites. No complaint or portal submission is required...

Citations: `immunization-clinic-access-faq.txt`, `general-intake-faq.txt`,
`resolved-cases-q2-2026.txt` — deduplicated to one card per source document even
though the underlying retrieval returned multiple chunks from the same file.

If the corpus genuinely doesn't cover a question (e.g. "what's the SLA for a full
highway repaving?"), the answer says so rather than extrapolating from a related
but different SLA.

---

## Employee Dashboard (`/employee`)

**Viewing as** — a department picker in its own toolbar row (full department names,
not codes — "Department of Housing and Urban Development", not "DHUD"). This is a
demo-only client-side stub, not real login (see `ARCHITECTURE.md`'s limitations). It
persists across page reloads via `localStorage`.

Below the picker, a 3-stat summary row: **Pending review** (count, cross-department),
**{department} queue** (count for the selected department), **SLA breaches** (flagged
red if non-zero).

### Pending Review tab

A paginated table of every grievance currently paused at the human-review gate,
regardless of department (there's no department to filter by yet — that's exactly
what's undetermined).

![Pending Review queue](images/07-employee-pending-review.png)

Clicking **Review** opens a dialog showing:

- The citizen's original complaint text.
- Any follow-up detail the citizen added via the inline clarification form (see
  above), rendered as its own timestamped section distinct from the original
  complaint — not blended into one paragraph — since a supervisor needs to see at a
  glance what was added and when, not just that more text exists somewhere in the
  block.
- The LLM's confidence score and its reasoning (why it didn't commit to a guess).
- Editable Department / Category / Priority fields, each defaulting to "(keep as-is)"
  — a supervisor only needs to fill in what they're actually overriding.
- A required review note and "Reviewed by" field.

![Review dialog with a citizen follow-up shown as a distinct entry](images/13-employee-review-dialog-followups.png)

Clicking **Confirm & Route** resumes the paused LangGraph4j workflow with the
decision; the grievance moves to `TRIAGED` with `department_confirmed` now set
(distinct from `department_predicted`, which still shows what the AI originally
guessed).

![Review dialog — citizen text, LLM reasoning, editable decision form](images/08-employee-review-dialog.png)

Opening the same dialog for a grievance that was *never* part of an active workflow
(a seeded demo row, for instance) shows a read-only detail view instead of an editable
form — there's nothing to approve for a case that never paused.

### {Department} Queue tab

A paginated table scoped to the selected department, any status: submitted date,
status, category, priority (color-coded chip), SLA due date, with a red row highlight
and **BREACHED** flag for anything past due and still open. **View** opens the same
detail dialog in its read-only mode.

![Department Queue, scoped to DOT](images/09-employee-department-queue.png)

### Trends tab

Two independent toggles: **This Department / All Departments**, and a **7 / 30 / 90
day** window. Four charts plus an SLA snapshot:

- **Volume over time** — daily complaint count, line chart.
- **Sentiment trend** — average sentiment score per day (-1 to 1); days with no
  sentiment-bearing complaints are omitted from the series rather than plotted as a
  misleading 0.
- **Top categories** — bar chart, top 8 in the selected scope.
- **By priority** — bar chart, CRITICAL/HIGH/MEDIUM/LOW, color-matched to the same
  priority-chip colors used everywhere else in the app.
- **SLA snapshot** — three stat cards: *Resolved on time*, *Resolved late*, *Currently
  breached (open)*. Deliberately three numbers, not one "compliance %" — a closed-late
  case and a still-open breach are different problems worth seeing separately.

![Trends tab — volume, sentiment, category, priority charts + SLA snapshot](images/10-employee-trends.png)

---

## What's real vs. demo-only

Worth being explicit about, since it affects how to read a walkthrough of a portfolio
project:

- **Real**: the LLM classification, the RAG retrieval/rerank/citation pipeline, the
  LangGraph4j pause/resume workflow, the MCP tool server, the database and its
  aggregation queries. These all run against a live Ollama instance and a live
  Postgres instance — nothing in the walkthrough above is mocked.
- **Demo-only**: the department picker (no real login), and the later status
  transitions (`ROUTED`/`IN_PROGRESS`/`RESOLVED`/`CLOSED`) which exist in the schema
  and the MCP tool's validation but have no dashboard UI yet — a real deployment would
  need a caseworker view for actually working a ticket after it's routed, not just
  approving its classification.
