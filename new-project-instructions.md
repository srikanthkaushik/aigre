# Project instructions — Java AI business application

*Paste §1 into the project's custom instructions. Keep the whole file in the
project as a reference, alongside `devdocs-ai-skilljar-alignment.md`.*

---

## 1. Instructions (paste this part)

You are my implementation partner on a production-grade Java AI application.
This is a **build project, not a tutorial** — I've already worked through the
concepts. Optimise for shipping.

**Canonical stack — don't propose alternatives:**
Java 21, Spring Boot 4.0.6, LangChain4j 1.18.0 (via `langchain4j-bom`),
LangGraph4j 1.8.20, Spring AI 2.0.0 GA (MCP server only), pgvector, Ollama.
**Never suggest Python or LangChain (Python).**

**Environment:** Windows, **Command Prompt only** — not PowerShell, not bash.
CMD syntax and Windows escaping on every command and path. VS Code, Maven.

**How to work:**
- **Give me complete, working code.** Full files or clearly-scoped diffs — not
  hints, not partial snippets I have to assemble. Skip the pedagogy.
- Explain only what's non-obvious: a tradeoff, a gotcha, or a decision I should
  weigh in on. One or two lines, then move on.
- **Search before writing code for any version-sensitive dependency.** Fetch
  official docs for exact artifact coordinates, property names, and API
  signatures. Training data on these libraries is routinely stale, and this
  has saved hours repeatedly.
- Batch related work — if three files change together, give me all three.
- Flag honest tradeoffs before I commit, including when something I proposed
  is the weaker option.
- **File drift is a recurring problem.** Before debugging behaviour, confirm
  the relevant files still contain earlier edits.
- Keep `PROJECT.md` current at natural checkpoints — decisions, gotchas,
  what's done.

---

## 2. Day-one scaffold

Goal: a running skeleton with every layer wired before any business logic.
Should take one working session.

- [ ] **Infra** — pgvector container, Ollama models pulled
      (`nomic-embed-text`, `qwen2.5:7b`)
- [ ] **Scaffold** — Spring Boot 4.0.6, WebFlux, package `com.<x>.<y>` (no
      underscores)
- [ ] **`pom.xml`** — `langchain4j-bom` in `<dependencyManagement>`; add
      `langchain4j`, `-anthropic`, `-ollama`, `-pgvector`,
      `-document-parser-apache-tika`, `-mcp`, plus `starter-jdbc`, `postgresql`,
      `starter-actuator`
- [ ] **Dual provider** — `llm.provider=anthropic|ollama` switching both
      `ChatModel` and `StreamingChatModel`
- [ ] **RAG** — `OllamaEmbeddingModel` + `PgVectorEmbeddingStore` with
      `.dimension(embeddingModel.dimension())`
- [ ] **Ingestion** — `EmbeddingStoreIngestor`, Tika parser, `source` metadata,
      and a `POST /ingest/reset?confirm=true` that wipes and reseeds a
      **declared** corpus
- [ ] **Retrieval** — hybrid (vector + Postgres FTS), **retrieve 3× then
      rerank**, return cited sources
- [ ] **Streaming endpoint** — SSE, structured JSON output
- [ ] **Eval skeleton** — one deterministic retrieval test that passes
- [ ] **Metrics** — Actuator + a `Timer` on each LLM call

Only then start on the domain.

---

## 3. Test data

No real corpus exists yet, so generate one — but generate it *deliberately*.
The corpus determines whether the evals mean anything.

**Generate to a written spec, commit to `test-data/`, never regenerate at
runtime.** Ask Claude to produce documents one at a time against the spec.
Pair them with a `corpus-manifest.md` listing each document, its purpose, and
which eval questions it answers.

### Document corpus

- **100–150 documents** in the domain's real shapes — policies, contracts, SOPs,
  product manuals, resolved cases. Aim for **several hundred chunks**; the
  previous project's 81 was too small for retrieval to be interesting.
- **Realistic messiness.** Headers and footers, tables, boilerplate, legal
  preamble, cross-references between documents, inconsistent formatting.
  Clean uniform prose makes RAG look far better than it is.

### Build in the things that make evals meaningful

| Include | Why |
|---|---|
| **Ground truth** — each eval question maps to a known source document | Deterministic retrieval assertions, no LLM judge needed |
| **Distractors** — near-miss documents on adjacent topics | This is what surfaces the cosine-can't-judge-relevance problem. Without them retrieval looks perfect. |
| **Negatives** — questions the corpus deliberately cannot answer | Tests refusal vs. hallucination. The system should say it doesn't know. |
| **Superseded versions** — an old policy plus its replacement | Tests recency handling and conflicting-source behaviour |
| **Fake PII** — obviously-fake SSNs, cards, emails | Makes guardrails testable end to end |

### Systems-of-record data

Tools need structured data, not documents. Seed SQL for whatever the domain's
records are — claims, orders, suppliers, tickets. Deliberately include edge
cases: missing fields, stale records, IDs that don't exist. That's what
exercises the tool error paths, which is where tool design actually gets
tested.

---

## 4. Milestones

| # | Deliverable |
|---|---|
| 0 | Domain model, test data (§3), and a written definition of "correct" |
| 1 | Core scaffold (§2) running end to end |
| 2 | Domain RAG — real corpus, reranked, cited |
| 3 | Tools over MCP against the systems of record |
| 4 | Agent workflow with a human approval gate (`interruptBefore` + checkpointing) |
| 5 | **Frontend chatbot** (§5) |
| 6 | Hardening — evals, guardrails, observability |

**Build the eval suite at milestone 0–1, not 6.** Define correctness before
building the thing meant to be correct. Deterministic assertions where
possible; LLM-as-judge only where they aren't.

---

## 5. Frontend chatbot — Angular

**Angular 22** (current stable, June 2026 — verify before scaffolding).
Standalone components, signals, **zoneless by default**.

**Layout:** separate `frontend/` project alongside the Spring Boot app.
- **Dev:** `ng serve` on 4200, with `proxy.conf.json` forwarding `/api` to
  `localhost:8080`. Avoids CORS entirely — do this rather than configuring
  CORS on the backend.
- **Prod:** `ng build --output-path=../src/main/resources/static` so the whole
  thing ships as one Spring Boot jar.

Features, in order:

1. **Streaming chat** — render tokens as they arrive
2. **Citations** — `sources` as clickable links under each answer
3. **Agent progress** — show the node trace live (`planner → retriever → …`)
   so a 25-second run doesn't look frozen
4. **Approval gate** — when the graph interrupts, render the draft with
   approve / request-changes buttons that resume the run by `threadId`
5. **Conversation history** — thread-scoped, backed by the checkpointer

**Angular-specific gotchas:**

- **Don't use `HttpClient` for token streaming.** It buffers the full response
  by default. `observe: 'events'` with `reportProgress: true` gives partial
  text but re-delivers the whole accumulated body each time — workable but
  clumsy. Use `fetch()` with a `ReadableStream` reader inside a service.
- **`EventSource` cannot do POST.** GET-only, so it can't carry a JSON body.
  Same answer: `fetch()` + `ReadableStream`, parsing `data:` lines yourself.
  (Or move the endpoint to WebSocket.)
- **Zoneless means manual change detection is gone, not automatic.** Async
  work outside Angular's knowledge won't trigger a re-render. Hold streaming
  state in a **`signal`** and update it per chunk — that's what drives the
  view. Don't reach for `NgZone.run()`; it's the zone-based workaround.
- **Never render model output as raw HTML.** `[innerHTML]` runs through
  Angular's sanitiser, which strips a lot but isn't a markdown renderer —
  use a markdown library that escapes by default, and never
  `bypassSecurityTrustHtml` on model output.

**Backend decision to make first:** streamed output is raw JSON tokens if the
endpoint enforces a schema, so the UI would render `{"answer": "conn` character
by character. Either buffer and parse at completion (simple, loses the
streaming feel) or stream the prose field separately from the structured
payload (better UX, more backend work). Decide before building the UI.

---

## 6. Carried-forward gotchas (don't re-derive)

| Trap | Reality |
|---|---|
| Spring Boot 4 ships **Jackson 3** | `tools.jackson.*`, not `com.fasterxml.jackson.*` |
| LangChain4j 0.x → 1.x | `StreamingChatLanguageModel`→`StreamingChatModel`, `generate()`→`chat()`, `StreamingResponseHandler`→`StreamingChatResponseHandler`, `onNext()`→`onPartialResponse()`, `onComplete()`→`onCompleteResponse()` |
| Anonymous inner classes | Break WebFlux streaming handlers — extract to a named class |
| Spring AI MCP | Milestones at `https://repo.spring.io/milestone`. `spring.ai.mcp.server.protocol=STREAMABLE` (uppercase). SSE deprecated. |
| Spring AI 1.x + Spring Boot 4 | `mcp-spring-webmvc:0.12.1` compiled against Spring 6 → `NoSuchMethodError: HttpHeaders.containsKey`. Upgrade to Spring AI **2.0.0 GA**, which ships its own transport built for Spring 7 |
| `@Tool`/`@ToolParam` in Spring AI 2.0 MCP server | Wrong annotations for MCP — use `@McpTool`/`@McpToolParam` from `org.springframework.ai.mcp.annotation`. The old ones are for non-MCP AI tools |
| `SyncMcpToolProvider: No tool methods found` | Add a `MethodToolCallbackProvider` bean explicitly — the annotation scanner is a separate registration path from it, both must coexist |
| `BeanDefinitionOverrideException` | `@Bean` method name must differ from its own `@Configuration` class name |
| MCP client/server mismatch | `StreamableHttpMcpTransport.url(...)`, **not** `HttpMcpTransport.sseUrl(...)`. Probe endpoints — `Invalid Accept header` on `/mcp` means Streamable is live. |
| MCP client eager connect | Build the client *inside* the `ToolProvider` bean and catch; return an empty `ToolProviderResult` on failure, or the app won't boot without the MCP server |
| Embedding dimensions | `nomic-embed-text` is **768**, not 1536. Use `.dimension(embeddingModel.dimension())`. |
| Offline boot | `@Value("${anthropic.api-key:}")` — trailing colon allows no key at all |
| Package names | No underscores; scan starts at `@SpringBootApplication`'s package |
| Test files | Must live in `src/test/java` — test-scope deps are invisible to main sources |
| `Mono<Void>` | Never `switchIfEmpty` on it — always completes empty, so it fires on the happy path |
| `Flux.just(dataBuffer)` | Single-use; wrap in `Flux.defer` |
| Guardrails | `WebFilter`, not `HandlerInterceptor` (that's MVC) |
| `JShell.eval()` | Takes exactly one snippet — split with `sourceCodeAnalysis().analyzeCompletion()` or the rest is silently ignored |
| LangGraph4j interrupt detection | `InterruptionMetadata` is **not** a `NodeOutput` — don't rely on `invoke()`'s return type to tell pause vs. complete. Call `graph.getState(config).next()` after `invoke()`; it equals the gate node's id when paused, else null/END |
| LangGraph4j resume | Use `graph.invoke(GraphInput.resume(map), config)` with the **same** `threadId` — passing a plain `Map` to `invoke()` restarts from START instead of resuming |
| Polling paused-run state from a separate request | `next() != gateNodeId` only means "finished" when read synchronously right after your own `invoke()`/`resume()` call. A concurrent poll can catch a checkpoint mid-transition (past the gate, next node not yet run) and misreport "done" with stale state. Check actual terminal `next() == null \|\| END.equals(next())` instead |
| `mvn -pl <module> spring-boot:run` on Windows spawns two `java.exe` | One is the Maven launcher, the other the real app. Killing the launcher's PID doesn't free the port — find the real PID via `Get-CimInstance Win32_Process -Filter "Name='java.exe'" \| Where CommandLine -match '<MainClass>'` |
| `@GetMapping("/{var}")` with a dotted value (`foo.md`) as the last path segment | Default Spring path matching strips the trailing dot as a format-suffix separator — silent 404. Fix: `@GetMapping("/{var:.+}")` |
| Shell variables across separate Bash tool calls | Don't persist — each invocation may be a fresh shell. Keep a capture→use sequence (e.g. curl an id, then use it) in one call |

---

## 7. Design rules that earned their place

**Reason before verdict.** Any structured judgement from a model — a reviewer,
a scorer, a classifier — must reason first and emit the verdict on the final
line behind a labelled marker. A bare verdict fails in *both* directions.
Parse with `Pattern.compile("SCORE:\\s*(\\d+)")`; use `-1` for unparseable,
never `0`.

**Cosine can't judge relevance.** Measured an irrelevant chunk at 0.789
outscoring a relevant one at 0.788. `minScore` thresholds don't fix it.
Retrieve wide, rerank narrow with a cross-encoder (`OnnxScoringModel`) or an
LLM scoring pass. Cheap — retrieval was 1% of run time.

**Tool design is engineering.** Tool quality *is* answer quality. A signature
that requires an argument the user never gave will get a fabricated one — let
the model signal uncertainty instead. Filter tool output. Make errors *teach*
what to do next.

**Instrument before optimising.** In the last project the critic node was 53%
of runtime, more than the coder it reviewed. Not guessable.

**Every agent cycle needs a termination condition you control.** The
framework's recursion limit is a backstop, not a design.

---

## 8. Picking the use case

Needs all five layers to be genuinely load-bearing:

| Layer | Requires |
|---|---|
| RAG | A real document corpus that changes over time |
| Tools | External systems of record to query or act on |
| Agents | Multi-step work with a review or approval step |
| Guardrails | PII, regulatory, or financial exposure |
| Evals | A definable notion of a correct outcome |

**Candidates:** insurance claims triage (scores highest on all five) · vendor
contract review · customer support deflection · supplier onboarding and
compliance.

**Avoid:** clinical/diagnostic (regulatory weight swamps the engineering), and
anything where "correct" is purely subjective (evals become meaningless).

---

## 9. Opening the project

Tell Claude: the use case and the business workflow it automates · who the end
user is and what a wrong answer costs them · what systems of record exist (real
or simulated) · whether you're starting on Anthropic or Ollama.

Then ask for the milestone-0 plan, the test-data spec (§3), and the day-one
scaffold.
