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
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final CitizenChatAssistant citizenChatAssistant;
    private final RetrievalService retrievalService;
    private final DepartmentDirectory departmentDirectory;
    private final CitizenTokenService citizenTokenService;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ChatController(
            CitizenChatAssistant citizenChatAssistant,
            RetrievalService retrievalService,
            DepartmentDirectory departmentDirectory,
            CitizenTokenService citizenTokenService,
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.citizenChatAssistant = citizenChatAssistant;
        this.retrievalService = retrievalService;
        this.departmentDirectory = departmentDirectory;
        this.citizenTokenService = citizenTokenService;
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
        return Mono.fromCallable(() -> new RetrievalWithContext(
                        retrievalService.retrieve(question.question(), department),
                        citizenContext(question.citizenToken())))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(rc -> streamAnswer(question.question(), rc.sources(), rc.citizenContext()));
    }

    /** Bundles the two blocking lookups stream() needs so both run in the same boundedElastic hop. */
    private record RetrievalWithContext(List<RetrievedSource> sources, String citizenContext) {
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

    private Flux<ServerSentEvent<String>> streamAnswer(String question, List<RetrievedSource> sources, String citizenContext) {
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
        new SseTokenStreamBridge(sink, sample, meterRegistry).attach(citizenChatAssistant.chat(prompt));

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
