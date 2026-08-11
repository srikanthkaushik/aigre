package com.aigre.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * One Timer per LLM call type (classification / sentiment / chatbot / rerank),
 * tagged so per-call-type cost is visible from day one -- see plan §"instrument
 * before optimising": the critic node was 53% of runtime in the last project
 * and that wasn't guessable without a timer on every call site.
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
