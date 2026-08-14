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
- **The department taxonomy is hardcoded in three independent places**, all of
  which would need to become agency-configurable:
  1. `schema.sql`'s seed `INSERT`s (six literal rows).
  2. The frontend's `DEPARTMENTS`/`DEPARTMENT_NAMES` constants
     (`frontend/src/app/core/models.ts`), hand-synced against the seed data —
     the file's own comment admits "the frontend has no live 'list departments'
     endpoint."
  3. **The largest single piece of unplanned-for work**: `LlmGrievanceClassifier`'s
     prompt bakes the six departments' jurisdiction descriptions and worked
     examples in as literal Java string content, not data read from a table.
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

**Within Model B** (the agency owns the data): AIGRE deliberately does **not**
reach into an agency's external system to look anything up. Building bespoke
connectors to an unbounded set of unknown legacy CRMs is an unscoped
integration-matrix problem, not a feature. Instead:
- **Default**: the agency optionally includes context in the classify request
  itself — a summary of prior related cases, account history, whatever they
  judge relevant. AIGRE never fetches it; the agency's own system decides what
  to push in. This keeps AIGRE's integration surface to one HTTP contract, not
  N connectors to N legacy systems.
- **Optional, later** (Phase 5 below): a lightweight "shadow index" an agency
  can choose to feed AIGRE — a webhook push or batch sync of just
  department/category/timestamp/external-id — purely so AIGRE can offer
  duplicate-candidate *suggestions* back. The agency's system stays
  authoritative; AIGRE never claims ownership of the record.

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

### Phase 4 — Classification-as-a-Service API (Model B, brownfield)
A genuinely new, separate, stateless endpoint — **not** a mode flag bolted onto
`GrievanceController`/`GrievanceWorkflowController`, since both are
persistence-first by construction and neither has an existing branch that
skips storage. A new controller calls `LlmGrievanceClassifier.classify()` and
`SlaCalculator` directly, with no `grievances` row ever written — this is
exactly the "already composable as a stateless service" property the current
code already has, just never exposed as its own product surface.

Auth here is a deliberately different path: API-key-based, system-to-system —
not employee JWT, not a citizen session, because brownfield integration is
service-to-service, not a human logging in. The request contract takes raw
complaint text, a reference to the agency's own taxonomy/config (Phase 2), and
an optional agency-supplied-context field (see the lookup section above). The
response is the classification result — department, category, priority,
confidence, reasoning — nothing more.

**Explicitly out of scope for this mode, stated as a deliberate design
boundary, not a gap**: duplicate detection (nothing AIGRE-owned exists to
compare against), the human-review workflow and its persistence, and the
dashboard/portal — the agency's own system owns all of that. Worth supporting
both a synchronous REST response and an async webhook-callback option, since
LLM-backed classification takes a few seconds and not every brownfield
integration pipeline is synchronous-request-friendly.

### Phase 5 — Optional shadow index for cross-system duplicate suggestions
Explicitly speculative — not built unless a real brownfield agency asks for it.
For agencies wanting duplicate-detection-like value without ceding ownership of
their system of record: an optional webhook push or batch sync populating a
minimal AIGRE-side index (department, category, timestamp, external ID only)
purely to support duplicate-candidate *suggestions* returned alongside a
classification. The agency's system remains authoritative; AIGRE never claims
the record as its own.

## Explicit non-goals for now

- No bespoke per-CRM connectors. The agency-supplied-context contract in
  Phase 4 is the intentional integration boundary, not a placeholder for a
  future connector catalog.
- No database-per-tenant infrastructure until an actual agency's compliance
  requirement demands it — named above as the alternative, not built
  speculatively.
- No Phase 5 shadow index until a real brownfield customer specifically asks
  for it.
- No schema changes, no agency-provisioning UI, no API-key auth path has
  started — this is a design reference, not a spec ready to implement.
