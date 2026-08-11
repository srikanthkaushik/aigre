package com.aigre.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * One Timer per instrumented pipeline step (classification / rerank / embed /
 * vector_search, and any future call type), tagged so per-step cost is visible from
 * day one -- see plan §"instrument before optimising": the critic node was 53% of
 * runtime in the last project and that wasn't guessable without a timer on every
 * call site. Covers both real LLM inference calls and the non-LLM pgvector search
 * step around them -- one metric family so a single dashboard panel can compare
 * "which step is actually slow" across the whole retrieval pipeline, not just the
 * LLM-only portion of it. The chat endpoint's streaming generation call doesn't fit
 * this class's synchronous Supplier shape (see SseTokenStreamingHandler instead,
 * which times it directly via Timer.Sample against the async completion callback).
 */
@Component
public class LlmCallTimer {

    private final MeterRegistry meterRegistry;

    public LlmCallTimer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public <T> T time(String callType, Supplier<T> call) {
        Timer timer = Timer.builder("aigre.llm.call")
                .tag("call_type", callType)
                .publishPercentileHistogram()
                .register(meterRegistry);
        return timer.record(call);
    }
}
