package com.aigre.chat;

import com.aigre.retrieval.RetrievalService;
import com.aigre.retrieval.RetrievedSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
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
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final CitizenChatAssistant citizenChatAssistant;
    private final RetrievalService retrievalService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ChatController(
            CitizenChatAssistant citizenChatAssistant,
            RetrievalService retrievalService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.citizenChatAssistant = citizenChatAssistant;
        this.retrievalService = retrievalService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatQuestion question) {
        return Mono.fromCallable(() -> retrievalService.retrieve(question.question()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(sources -> streamAnswer(question.question(), sources));
    }

    private Flux<ServerSentEvent<String>> streamAnswer(String question, List<RetrievedSource> sources) {
        String context = sources.stream().map(RetrievedSource::text).collect(Collectors.joining("\n---\n"));
        String prompt =
                """
                Answer the citizen's question using only the context below, UNLESS the question asks
                about the status, SLA, or duplicate history of a specific grievance identified by its
                ID (a format like G0001) -- for that, call the available tool to look up live data
                instead of guessing from the context. If neither the context nor a tool call answers
                the question, say you don't know -- never guess.

                Context:
                %s

                Question: %s
                """
                        .formatted(context, question);

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
