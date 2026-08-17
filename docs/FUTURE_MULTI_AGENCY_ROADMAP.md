# Future state: onboarding new agencies (greenfield and brownfield)

**Status: not built.** Documented here as a phased roadmap for a future capability,
deliberately deferred — see [`PROJECT.md`](../PROJECT.md#open-items-to-revisit) for
how open items are tracked in this project. This file exists so the design has a
home to come back to, not as a spec ready to implement.

## The problem this would solve

AIGRE has been built greenfield end to end: one agency (a single city), six
departments it owns outright, one Postgres database it's the sole writer to.
Real-world adoption means two very different onboarding stories, and they need
different architecture, not one "multi-tenant" checkbox:

- **Model A — full-adoption agencies.** A new city or county wants the whole
  AIGRE experience — citizen portal, employee dashboard, storage, the
  human-review workflow — as their own isolated tenant.
- **Model B — brownfield agencies.** An agency already runs a system of record
  (a legacy 311/CRM/case-management platform) and does **not** want AIGRE to own
  storage or routing. They want only the AI layer — classification and
  prioritization — bolted onto their existing system, with routing skipped
  entirely.

Every claim below was checked directly against the current code (schema, auth,
query-scoping, RAG ingestion/retrieval, the classifier, MCP tools, the SLA
calculator, duplicate detection, deployment config), not assumed.

## What's actually true today

- **Zero tenant/agency concept anywhere.** `departments.id` is a bare
  `VARCHAR(10)` primary key, globally unique with no qualifier — two agencies
  both wanting a `DPW` code would collide today. `grievances` has no
  organization-scoping column at all — the single biggest gap. `department_
  employees.username` is globally `UNIQUE`, so two agencies couldn't even have
  two different people both named `jsmith`.
- **The department taxonomy is no longer hardcoded** — see PROJECT.md's
  "Onboard a new department from a public URL" for the full build. All three
  places named below now read from the `departments` table live:
  1. `schema.sql`'s seed rows still exist (for a fresh database's first boot),
     but a new `POST /admin/departments` endpoint inserts new rows at runtime.
  2. The frontend's department list (`frontend/src/app/core/department
     .service.ts`) fetches `GET /departments` live instead of the old
     hardcoded `DEPARTMENTS`/`DEPARTMENT_NAMES` constants.
  3. `LlmGrievanceClassifier`'s prompt now interpolates its DEPARTMENTS
     section from `DepartmentDirectory` (an in-memory cache over the
     `departments` table, refreshed on new-department onboarding), not a
     literal Java string. Its worked EXAMPLES section (a few illustrative
     reasoning samples, not an exhaustive per-department list) is still
     hardcoded prose — deliberately: examples teach the reasoning *pattern*,
     they don't need one per department to do that.

  **Still genuinely future work, unaffected by the above**: none of this
  adds an agency/tenant qualifier. `departments.id` is still a bare
  `VARCHAR(10)` primary key, globally unique with no agency scope — two
  agencies both wanting a `DPW` code would still collide. This closed the
  "hardcoded taxonomy" problem for single-agency dynamic departments, not
  the multi-agency tenant-isolation problem described below.
- **Classification is already a stateless, side-effect-free function.**
  `LlmGrievanceClassifier.classify(String rawText)` has exactly three
  dependencies — a `ChatModel`, an `ObjectMapper`, and a Micrometer timer — no
  `NamedParameterJdbcTemplate`, no department-table lookup, no app state.
  `SlaCalculator`/`Priority` are equally pure (the class's own doc comment says
  so). Together, classification and SLA computation could be composed into a
  stateless service today with zero persistence touched.
- **But every existing HTTP entry point is persistence-first.** Both the plain
  intake path (`GrievanceController.submit()`) and the workflow path
  (`GrievanceWorkflowController.start()`) insert a `grievances` row before or
  immediately after classifying — there is no existing "classify only, don't
  commit" path today. The workflow graph's `classify` node calls a direct JDBC
  write (`persistPredictedClassification()`) inline in the same method as the
  LLM call — separable, but not separated.
- **Duplicate detection is fundamentally AIGRE-data-dependent, not a general
  utility.** `DuplicateDetectionService.findOpenDuplicate()` queries AIGRE's own
  previously-stored `grievances` rows. It has nothing to compare against for an
  agency whose case history lives in their own external system.
- **The RAG corpus has no isolation at all**, not even along the one dimension
  it already carries metadata for. `RetrievalService.retrieve()` builds an
  `EmbeddingSearchRequest` with no metadata filter — every query searches the
  entire `rag_documents` table regardless of department, let alone agency. A
  second agency's corpus sharing the same table today would produce
  cross-contaminated chatbot answers.
- **Infra is single-everything**: one hardcoded Postgres database name
  (`aigre`), one Spring Boot process/port, one global JWT signing secret, one
  global corpus filesystem path, no `docker-compose.yml`, no schema-per-tenant
  or database-per-tenant pattern anywhere in the repo.

## Directly answering "how does it look up agency-specific records"

This is genuinely two different problems depending on which model an agency is:

**Within Model A** (AIGRE owns the data): close to trivial, and close to the
existing pattern. `GrievanceQueryService.list()` already builds its department
filter server-side as an optional `AND` clause (`WHERE 1=1 ... AND
COALESCE(department_confirmed, department_predicted) = :department`), derived
from the authenticated principal — the controller never trusts a
client-supplied department except for `ADMIN`. `DepartmentAccess.
requireOwnDepartment` is a separate post-hoc equality check for by-ID access.
Both generalize additively: one more `AND agency_id = :agencyId` clause, and a
parallel `requireOwnAgency` check — **once `grievances` gains an `agency_id`
column.** No architectural rewrite needed here; this is the easiest part of the
whole system to extend, because the existing pattern (optional filter clause,
principal-derived not client-trusted) already composes with one more dimension.

**Within Model B** (the agency owns the data): AIGRE reaches **out**, live,
during inference — it doesn't wait for the agency to push context in, and it
doesn't replicate the agency's data into its own database either. This is a
better fit for AIGRE than a passive contract, not just a different one: AIGRE
already speaks MCP — it runs an MCP **server** today
(`com.aigre.tools.GrievanceMcpTools`, 5 tools over its own data). The client
side has always been the deferred half — see this doc's own [Known
Limitations](ARCHITECTURE.md#known-limitations) cross-reference: *"the workflow
graph writes to Postgres directly rather than calling the MCP tools... not yet
wired to each other."* This is the natural generalization of that same deferred
wiring, pointed outward at a third party's tools instead of just AIGRE's own.

Two integration shapes, both converging on the same principle — AIGRE calls
out live, the agency stays the source of truth:

1. **Agency already runs (or stands up) an MCP server.** AIGRE connects as an
   MCP *client* — `langchain4j-mcp`'s `McpToolProvider` against a Streamable
   HTTP transport. Two gotchas already known in this project's own `CLAUDE.md`
   apply directly:
   - `StreamableHttpMcpTransport.url(...)`, not the deprecated
     `HttpMcpTransport.sseUrl(...)`.
   - Build the client *inside* the `ToolProvider` bean and catch; return an
     empty `ToolProviderResult` on failure, so one agency's unreachable system
     doesn't prevent AIGRE from booting or serving anyone else.
2. **Agency only has a REST/legacy API, no MCP support** — the realistic
   default for brownfield legacy systems. AIGRE hosts a thin **adapter layer**:
   a generically-configured MCP server that wraps an agency's REST calls as MCP
   tools, driven by declarative per-agency config (tool name, description,
   endpoint, auth, request/response field mapping) rather than hand-written
   Java per agency. This is what turns "unbounded bespoke connectors" into one
   reusable, configurable pattern — what's agency-specific is *config*, not
   code. See Phase 5 below.

**How this changes classification.** `LlmGrievanceClassifier.classify()` today
is a single-shot `chatModel.chat(prompt)` call with no tool access. Supporting
live lookups means moving to LangChain4j's `AiServices` pattern (or explicit
`ChatRequest`/`ToolSpecification` wiring) with the agency's `McpToolProvider`
bound in, so the model can decide mid-classification to call e.g.
`find_open_cases_at_address` or `lookup_citizen_account` before finalizing
department/priority/duplicate signals — grounded in the agency's live data, not
just the raw complaint text. This also revises, not just extends, one earlier
finding: duplicate detection isn't categorically impossible for Model B after
all. An agency exposing a `find_similar_cases`-shaped tool lets classification
delegate duplicate-signal-gathering to the agency's own live system, instead of
needing AIGRE's own `DuplicateDetectionService` or stored rows at all.

**How this changes chat.** Today's RAG (`RetrievalService`) is static/ingested
— right for policy/SOP questions, the wrong tool for "what's the status of MY
case" (account-specific, always-current). The same tool-calling capability
extends to the citizen chat agent: a case-status question gets answered by
calling the agency's live status tool, not by searching the ingested corpus —
two distinct mechanisms for two distinct question types, not one retrieval path
doing double duty.

**Trust/safety considerations this introduces, worth naming rather than
deferring silently:**
- Same tool-design discipline this project already applies to its own tools:
  filter output, make errors teach, don't let a malformed/missing field
  silently corrupt a classification.
- **Default to read-only tool contracts.** `GrievanceMcpTools` mixes read/write
  because AIGRE owns that data; a new agency-facing contract should default to
  read-only unless an agency explicitly negotiates write access.
- Per-agency credential management (API keys/mTLS/whatever an agency's system
  requires) is a new surface — ties to Phase 1's agency-scoped data model, but
  for integration credentials, not citizen data.
- Latency/failure handling: classification now optionally depends on a live
  third-party call, and needs an explicit fallback (proceed using just the raw
  text if the agency tool call times out) — the same defensive pattern named
  above for the MCP client eager-connect gotcha, just pointed at a remote
  dependency instead of AIGRE's own server.

## Phased roadmap

### Phase 1 — Multi-agency data model foundation
Prerequisite for both models. A new `agencies` table; `agency_id` threaded onto
`departments` (composite primary key `(agency_id, id)` so department codes can
be reused across agencies without collision), `department_employees` (fixing
the global `UNIQUE(username)` to `UNIQUE(agency_id, username)`), `citizens`,
`sla_policies`, and — the biggest lift — `grievances` itself. Auth gains an
`agencyId` dimension: `EmployeePrincipal` (new field), `JwtService` (new claim,
both issue and parse), `AuthService` (agency predicate on login, agency-aware
username uniqueness), `DepartmentAccess` (a parallel `requireOwnAgency` check).
`GrievanceQueryService`/`GrievanceQueryController` gain the added filter
dimension described above. The frontend's `DEPARTMENTS`/`DEPARTMENT_NAMES`
constants stop being hardcoded and become a fetched-at-runtime endpoint (e.g.
`GET /agencies/{id}/departments`) — needed regardless of model, since a real
second agency has different departments by definition.

Infra decision to make explicitly here: shared Postgres with `agency_id`
row-level scoping (cheaper to operate, consistent with this project's existing
"minimal new infrastructure" bias — see [`ARCHITECTURE.md`](ARCHITECTURE.md)'s
pgvector-over-Qdrant reasoning) versus database-per-tenant, named as the
alternative for agencies with strict data-residency or compliance requirements
— a real consideration for government data, not a hypothetical one, but not
worth building speculatively.

### Phase 2 — Agency-configurable classification
Externalizes the department taxonomy, jurisdiction descriptions, and worked
examples currently hardcoded into `LlmGrievanceClassifier`'s prompt into
agency-scoped config, templated in at call time. The priority rubric
(`Priority`'s ack/resolve hours) becomes agency-configurable too, backed by
Phase 1's now-agency-scoped `sla_policies`. RAG isolation:
`CorpusIngestionService` gains an `agency` metadata field at ingestion
(alongside the existing `department` field, which is itself currently
decorative for retrieval — see below); `RetrievalService.retrieve()` gains an
actual metadata filter on its `EmbeddingSearchRequest`. This is additive —
langchain4j's pgvector store already supports metadata filtering — but it's
genuinely new work, not activation of a dormant field: today's retrieval
applies **no** metadata filter at all, not even along the one dimension
(`department`) it already tags chunks with.

### Phase 3 — Full multi-agency onboarding (Model A)
Agency provisioning, self-service or admin-assisted: create the agency row,
seed its departments, configure its SLA policy, ingest its policy corpus,
create its first `ADMIN` account. Worth naming as a low-cost nice-to-have:
per-agency branding, reusing the exact `ng generate @angular/material:m3-theme
--primary-color=... --tertiary-color=...` mechanism already used for AIGRE's
own civic-navy/amber palette — an agency's brand becomes two seed colors, not
a redesign.

### Phase 4 — Classification-as-a-Service API with live agency tool-calling (Model B, brownfield)
A genuinely new, separate endpoint — **not** a mode flag bolted onto
`GrievanceController`/`GrievanceWorkflowController`, since both are
persistence-first by construction and neither has an existing branch that
skips storage. A new controller wraps `LlmGrievanceClassifier` — moved from a
plain `chatModel.chat(prompt)` call to LangChain4j's `AiServices` pattern with
the agency's `McpToolProvider` bound in (see the mechanism above) — plus
`SlaCalculator`, with no `grievances` row ever written. Classification itself
stays stateless from AIGRE's own database's point of view even once it's
making live outbound tool calls — it still never reads or writes AIGRE's own
`grievances` table in this mode.

Auth here is a deliberately different path: API-key-based, system-to-system —
not employee JWT, not a citizen session, because brownfield integration is
service-to-service, not a human logging in. The request contract takes raw
complaint text, a reference to the agency's own taxonomy/config (Phase 2), and
the agency's registered MCP endpoint (or adapter config, Phase 5) so
classification knows what tools it's allowed to call. The response is the
classification result — department, category, priority, confidence,
reasoning, and now optionally which agency tools were consulted and what they
returned, for audit purposes.

**Explicitly out of scope for this mode, stated as a deliberate design
boundary, not a gap**: the human-review workflow and its persistence, and the
dashboard/portal — the agency's own system owns all of that. (Duplicate
detection moved from "impossible here" to "delegated to an agency tool if one
exists" — see the mechanism above; it's no longer a blanket exclusion.) Worth
supporting both a synchronous REST response and an async webhook-callback
option, since classification now potentially chains an LLM call with one or
more live tool calls, and not every brownfield integration pipeline is
synchronous-request-friendly.

### Phase 5 — Reusable API-to-MCP adapter for non-MCP agencies
Sequenced after Phase 4, not before: agencies that already run an MCP server
can be served as soon as Phase 4 ships (mechanism 1 above). This phase is for
the more common brownfield case — a REST/legacy API with no MCP support
(mechanism 2 above) — a generically-configured MCP server AIGRE hosts, wrapping
an agency's REST calls as MCP tools from declarative config (tool name,
description, endpoint, auth, request/response mapping) rather than
hand-written Java per agency. This is what keeps "brownfield integration"
bounded: one reusable adapter pattern to build and maintain, not N bespoke
connectors to N legacy systems.

## Explicit non-goals for now

- No write-capable tool contracts by default. Every agency-facing tool starts
  read-only; write access is something an agency would have to explicitly
  negotiate, not a default capability.
- The Phase 5 adapter's configuration tooling/UI doesn't exist yet — Phase 5
  describes the wrapping mechanism, not a self-service way to configure it.
- No database-per-tenant infrastructure until an actual agency's compliance
  requirement demands it — named above as the alternative, not built
  speculatively.
- No schema changes, no agency-provisioning UI, no API-key auth path, no MCP
  client wiring has started — this is a design reference, not a spec ready to
  implement.
