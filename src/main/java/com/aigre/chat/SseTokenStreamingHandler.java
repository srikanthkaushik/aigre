package com.aigre.chat;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Named class, not an anonymous inner class — anonymous classes break WebFlux
 * streaming handlers.
 *
 * Also the timing point for the chat endpoint's two streaming-specific latency
 * metrics -- time-to-first-token and total stream duration -- neither of which fits
 * LlmCallTimer's synchronous Supplier shape, since this call completes via an async
 * callback rather than a return value. A single Timer.Sample can be stopped against
 * more than one Timer (each stop() just measures elapsed-since-start against clock,
 * it doesn't consume the sample), so one sample records both metrics.
 */
public class SseTokenStreamingHandler implements StreamingChatResponseHandler {

    private final Sinks.Many<ServerSentEvent<String>> sink;
    private final Timer.Sample sample;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean firstTokenSeen = new AtomicBoolean(false);

    public SseTokenStreamingHandler(
            Sinks.Many<ServerSentEvent<String>> sink, Timer.Sample sample, MeterRegistry meterRegistry) {
        this.sink = sink;
        this.sample = sample;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        if (firstTokenSeen.compareAndSet(false, true)) {
            sample.stop(Timer.builder("aigre.chat.time_to_first_token").register(meterRegistry));
        }
        sink.tryEmitNext(ServerSentEvent.builder(partialResponse).event("token").build());
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        sample.stop(Timer.builder("aigre.chat.stream_duration").tag("outcome", "success").register(meterRegistry));
        sink.tryEmitComplete();
    }

    @Override
    public void onError(Throwable error) {
        sample.stop(Timer.builder("aigre.chat.stream_duration").tag("outcome", "error").register(meterRegistry));
        sink.tryEmitNext(ServerSentEvent.builder(String.valueOf(error.getMessage()))
                .event("error")
                .build());
        sink.tryEmitComplete();
    }
}
