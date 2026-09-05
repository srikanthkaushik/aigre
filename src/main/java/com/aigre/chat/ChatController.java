package com.aigre.chat;

import com.aigre.auth.CitizenTokenService;
import com.aigre.classification.DepartmentDirectory;
import com.aigre.retrieval.RetrievalService;
import com.aigre.retrieval.RetrievedSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Streams the answer prose token-by-token, then emits a final "sources"
 * event carrying structured, cited JSON — chosen over buffering the whole
 * structured payload so the UI still gets a live streaming feel (plan §5
 * backend decision).
 *
 * Goes through CitizenChatAssistant (an AiServices-backed TokenStream, see McpClientConfig)
 * rather than calling a raw StreamingChatModel directly, so the model can optionally call
 * AIGRE's own read-only MCP tools (get_grievance_status/check_sla_status/find_duplicate_chain)
 * for live per-grievance questions the static policy corpus can't answer.
 *
 * A returning citizen (one who provided contact info at some past submission) is silently
 * recognized via ChatQuestion.citizenToken() -- see CitizenTokenService's javadoc for why this
 * is a browser-issued token, never a typed email/identity lookup. Their recent grievance summary
 * is spliced into the grounding prompt so "what's the status of my complaint" resolves via the
 * existing tool-calling mechanism above without requiring an ID.
 *
 * ChatResponseCache sits in front of retrieval + the chat call for non-personalized questions --
 * see its javadoc and ChatResponseCache.CachedChatAnswer for the exact-match cache itself; the
 * eligibility rules (never cache a personalized or tool-triggering answer) live here since they
 * depend on request-shape decisions (citizenContext, tool calls) this controller already makes.
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final CitizenChatAssistant citizenChatAssistant;
    private final RetrievalService retrievalService;
    private final DepartmentDirectory departmentDirectory;
    private final CitizenTokenService citizenTokenService;
    private final ChatResponseCache chatCache;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ChatController(
            CitizenChatAssistant citizenChatAssistant,
            RetrievalService retrievalService,
            DepartmentDirectory departmentDirectory,
            CitizenTokenService citizenTokenService,
            ChatResponseCache chatCache,
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.citizenChatAssistant = citizenChatAssistant;
        this.retrievalService = retrievalService;
        this.departmentDirectory = departmentDirectory;
        this.citizenTokenService = citizenTokenService;
        this.chatCache = chatCache;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatQuestion question) {
        String department = question.department();
        if (department != null && !departmentDirectory.departmentIds().contains(department)) {
            return Flux.just(ServerSentEvent.builder("Unknown department: " + department).event("error").build());
        }
        // Optional wrapper, not a bare String -- citizenContext() legitimately returns null for the
        // (overwhelmingly common) anonymous case, and Mono.fromCallable treats a null-returning
        // Callable as completing empty, which would make flatMapMany below never run at all.
        return Mono.fromCallable(() -> Optional.ofNullable(citizenContext(question.citizenToken())))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(citizenContext -> respond(question.question(), department, citizenContext.orElse(null)));
    }

    /**
     * cacheEligible is false whenever citizenContext resolved -- a personalized answer must never
     * be read from or written to the shared cache (see class javadoc). On a cache hit, retrieval
     * and the chat call are skipped entirely, not just the chat call.
     */
    private Flux<ServerSentEvent<String>> respond(String question, String department, String citizenContext) {
        boolean cacheEligible = citizenContext == null;
        if (cacheEligible) {
            Optional<ChatResponseCache.CachedChatAnswer> cached = chatCache.get(question, department);
            if (cached.isPresent()) {
                meterRegistry.counter("aigre.chat.cache", "result", "hit").increment();
                return replayCached(cached.get());
            }
        }
        meterRegistry.counter("aigre.chat.cache", "result", cacheEligible ? "miss" : "bypass").increment();
        return Mono.fromCallable(() -> retrievalService.retrieve(question, department))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(sources -> streamAnswer(question, department, sources, citizenContext, cacheEligible));
    }

    /** Instant full-text reveal, not a simulated token trickle -- shows the cache-hit speed win directly. */
    private Flux<ServerSentEvent<String>> replayCached(ChatResponseCache.CachedChatAnswer cached) {
        return Flux.just(
                ServerSentEvent.builder(cached.answerText()).event("token").build(),
                ServerSentEvent.builder(toJson(cached.sources())).event("sources").build());
    }

    /**
     * null for an anonymous citizen, a fresh browser, or an invalid/expired token -- silently,
     * never an error (see ChatQuestion's javadoc). Non-null only once contact info was provided
     * at some past submission and CitizenTokenService.issueToken() minted a token for it.
     */
    private String citizenContext(String citizenToken) {
        if (citizenToken == null) {
            return null;
        }
        return citizenTokenService.parseToken(citizenToken).map(this::recentGrievanceSummary).orElse(null);
    }

    /** Capped at 5 -- keeps the prompt small and favors what's actually relevant to "my complaint" questions. */
    private String recentGrievanceSummary(UUID citizenId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT id, status, COALESCE(department_confirmed, department_predicted) AS department,
                       submitted_at
                FROM grievances WHERE citizen_id = :citizenId ORDER BY submitted_at DESC LIMIT 5
                """,
                new MapSqlParameterSource("citizenId", citizenId));
        if (rows.isEmpty()) {
            return null;
        }
        return rows.stream()
                .map(r -> "%s (%s, %s, submitted %s)"
                        .formatted(r.get("id"), r.get("status"), r.get("department"), r.get("submitted_at")))
                .collect(Collectors.joining("\n"));
    }

    private Flux<ServerSentEvent<String>> streamAnswer(
            String question, String department, List<RetrievedSource> sources, String citizenContext, boolean cacheEligible) {
        String context = sources.stream().map(RetrievedSource::text).collect(Collectors.joining("\n---\n"));
        String citizenSection = citizenContext == null
                ? ""
                : """

                This citizen has these of their own past submissions (only use this list if they ask \
                about their own grievance/complaint status, not for general policy questions):
                %s
                If they ask about "my complaint," "my most recent submission," or similar without \
                giving an ID, you already have enough information to act -- do NOT ask them which one \
                they mean or ask them to provide an ID. Immediately pick the single most relevant \
                grievance from the list above (most recent submission unless the question clearly \
                points to a different one) and call the appropriate tool with that ID to answer with \
                live status/SLA details.
                """.formatted(citizenContext);
        String prompt =
                """
                Answer the citizen's question using only the context below, UNLESS the question asks
                about the status, SLA, or duplicate history of a specific grievance identified by its
                ID (a format like G0001) -- for that, call the available tool to look up live data
                instead of guessing from the context. If neither the context nor a tool call answers
                the question, say you don't know -- never guess.
                %s
                Context:
                %s

                Question: %s
                """
                        .formatted(citizenSection, context, question);

        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        Timer.Sample sample = Timer.start(meterRegistry);
        SseTokenStreamBridge.CompletionListener completionListener = !cacheEligible
                ? (response, toolWasCalled) -> { }
                : (response, toolWasCalled) -> {
                    if (!toolWasCalled) {
                        chatCache.put(
                                question, department,
                                new ChatResponseCache.CachedChatAnswer(response.aiMessage().text(), sources));
                    }
                };
        new SseTokenStreamBridge(sink, sample, meterRegistry, completionListener).attach(citizenChatAssistant.chat(prompt));

        Flux<ServerSentEvent<String>> sourcesEvent =
                Flux.just(ServerSentEvent.builder(toJson(sources)).event("sources").build());

        return sink.asFlux().concatWith(sourcesEvent);
    }

    private String toJson(List<RetrievedSource> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (RuntimeException e) {
            return "[]";
        }
    }
}
