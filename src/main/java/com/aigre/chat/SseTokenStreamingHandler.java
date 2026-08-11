package com.aigre.chat;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

/**
 * Named class, not an anonymous inner class — anonymous classes break WebFlux
 * streaming handlers.
 */
public class SseTokenStreamingHandler implements StreamingChatResponseHandler {

    private final Sinks.Many<ServerSentEvent<String>> sink;

    public SseTokenStreamingHandler(Sinks.Many<ServerSentEvent<String>> sink) {
        this.sink = sink;
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        sink.tryEmitNext(ServerSentEvent.builder(partialResponse).event("token").build());
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        sink.tryEmitComplete();
    }

    @Override
    public void onError(Throwable error) {
        sink.tryEmitNext(ServerSentEvent.builder(String.valueOf(error.getMessage()))
                .event("error")
                .build());
        sink.tryEmitComplete();
    }
}
