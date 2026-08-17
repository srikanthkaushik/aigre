package com.aigre.chat;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Named class, not an anonymous inner class — anonymous classes break WebFlux streaming handlers.
 * Replaces SseTokenStreamingHandler now that the citizen chat goes through an AiServices-backed
 * TokenStream (CitizenChatAssistant) instead of a raw StreamingChatModel call, so it can also
 * support tool-calling. onToolExecuted only fires when the model actually calls a tool, so this
 * bridge handles both the RAG-only and tool-calling cases uniformly.
 *
 * Also the timing point for the chat endpoint's two streaming-specific latency metrics --
 * time-to-first-token and total stream duration -- neither of which fits LlmCallTimer's
 * synchronous Supplier shape, since this call completes via async callbacks rather than a return
 * value. A single Timer.Sample can be stopped against more than one Timer (each stop() just
 * measures elapsed-since-start against clock, it doesn't consume the sample), so one sample
 * records both metrics.
 */
public class SseTokenStreamBridge {

    private final Sinks.Many<ServerSentEvent<String>> sink;
    private final Timer.Sample sample;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean firstTokenSeen = new AtomicBoolean(false);

    public SseTokenStreamBridge(
            Sinks.Many<ServerSentEvent<String>> sink, Timer.Sample sample, MeterRegistry meterRegistry) {
        this.sink = sink;
        this.sample = sample;
        this.meterRegistry = meterRegistry;
    }

    public void attach(TokenStream tokenStream) {
        tokenStream
                .onPartialResponse(this::onPartialResponse)
                .onToolExecuted(this::onToolExecuted)
                .onCompleteResponse(this::onCompleteResponse)
                .onError(this::onError)
                .start();
    }

    private void onPartialResponse(String partialResponse) {
        if (firstTokenSeen.compareAndSet(false, true)) {
            sample.stop(Timer.builder("aigre.chat.time_to_first_token").register(meterRegistry));
        }
        sink.tryEmitNext(ServerSentEvent.builder(partialResponse).event("token").build());
    }

    private void onToolExecuted(ToolExecution toolExecution) {
        meterRegistry.counter("aigre.chat.tool_calls", "tool", toolExecution.request().name()).increment();
    }

    private void onCompleteResponse(ChatResponse completeResponse) {
        sample.stop(Timer.builder("aigre.chat.stream_duration").tag("outcome", "success").register(meterRegistry));
        sink.tryEmitComplete();
    }

    private void onError(Throwable error) {
        sample.stop(Timer.builder("aigre.chat.stream_duration").tag("outcome", "error").register(meterRegistry));
        sink.tryEmitNext(ServerSentEvent.builder(String.valueOf(error.getMessage()))
                .event("error")
                .build());
        sink.tryEmitComplete();
    }
}
