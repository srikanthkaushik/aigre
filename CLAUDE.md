# CLAUDE.md

You are my implementation partner on a production-grade Java AI application.
This is a **build project, not a tutorial** — I've already worked through the
concepts. Optimise for shipping.

---

## Canonical stack

Java 21, Spring Boot 4.0.6, LangChain4j **1.18.0** (via `langchain4j-bom`),
LangGraph4j **1.8.20**, Spring AI **2.0.0 GA** (MCP only), pgvector, Ollama.

**Never suggest Python or LangChain (Python). Don't propose alternative stacks.**

---

## Environment

Windows, **Command Prompt only** — not PowerShell, not bash.
CMD syntax and Windows escaping on every command and path. VS Code, Maven.

---

## How to work

- **Give complete, working code.** Full files or clearly-scoped diffs — not
  hints or partial snippets. Skip the pedagogy.
- Explain only what's non-obvious: a tradeoff, a gotcha, or a decision I should
  weigh in on. One or two lines, then move on.
- **Search before writing code for any version-sensitive dependency.** Fetch
  official docs for exact artifact coordinates, property names, and API
  signatures. Training data on these libraries is routinely stale.
- Batch related work — if three files change together, give me all three.
- Flag honest tradeoffs before I commit, including when something I proposed
  is the weaker option.
- **File drift is a recurring problem.** Before debugging behaviour, confirm
  the relevant files still contain earlier edits.
- Keep `PROJECT.md` current at natural checkpoints — decisions, gotchas,
  what's done.

---

## Carried-forward gotchas

| Trap | Reality |
|---|---|
| Spring Boot 4 ships **Jackson 3** | `tools.jackson.*`, not `com.fasterxml.jackson.*` |
| LangChain4j 0.x → 1.x | `StreamingChatLanguageModel`→`StreamingChatModel`, `generate()`→`chat()`, `StreamingResponseHandler`→`StreamingChatResponseHandler`, `onNext()`→`onPartialResponse()`, `onComplete()`→`onCompleteResponse()` |
| LC4j 1.18 handler package | `StreamingChatResponseHandler` and `ChatResponse` are in `dev.langchain4j.model.chat.response`, **not** `dev.langchain4j.model.chat`. Wrong package = cryptic "cannot be converted" error. |
| LC4j 1.18 `ChatModel.chat()` | Varargs overload exists: `chat(ChatMessage...)` → `ChatResponse`. Both `SystemMessage` and `UserMessage` pass as varargs. Return is `ChatResponse`, not `Response<AiMessage>`. |
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

## Design rules that earned their place

**Reason before verdict.** Any structured judgement from a model — reviewer,
scorer, classifier — must reason first and emit the verdict on the final line
behind a labelled marker. Parse with `Pattern.compile("SCORE:\\s*(\\d+)")`;
use `-1` for unparseable, never `0`.

**Cosine can't judge relevance.** Measured an irrelevant chunk at 0.789
outscoring a relevant one at 0.788. `minScore` thresholds don't fix it.
Retrieve wide, rerank narrow with a cross-encoder (`OnnxScoringModel`) or an
LLM scoring pass.

**Tool design is engineering.** Tool quality *is* answer quality. A signature
that requires an argument the user never gave will get a fabricated one — let
the model signal uncertainty instead. Filter tool output. Make errors *teach*
what to do next.

**Instrument before optimising.** In the last project the critic node was 53%
of runtime, more than the coder it reviewed. Not guessable.

**Every agent cycle needs a termination condition you control.** The
framework's recursion limit is a backstop, not a design.

---

## Historical reference

`devdocs-ai-skilljar-alignment.md` is historical reference from a prior project — consult it for working code patterns and past gotchas, but it is NOT a spec for this project; do not propose its features, phases, or domain.
