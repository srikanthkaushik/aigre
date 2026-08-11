package com.aigre.guardrail;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * Redacts PII (SSN, credit card, phone, email) embedded in citizen free-text fields before it's
 * stored or reaches the LLM. A WebFilter, not a HandlerInterceptor -- this app is WebFlux end to
 * end, and HandlerInterceptor is an MVC-only concept (CLAUDE.md).
 *
 * Scoped to exactly the 3 POST endpoints that carry citizen free text
 * (GrievanceIntakeRequest.rawText, ClarificationRequest.additionalText) -- the structured
 * citizenEmail/citizenPhone contact fields are deliberately left untouched, they're legitimate
 * contact info, not incidental PII typed into a complaint.
 *
 * Redaction volume is also exposed as a Counter (aigre.guardrail.pii_redacted, tagged by PII
 * type) alongside the WARN log line -- a log line alone isn't queryable/graphable, and "is this
 * actually happening in practice, and how often" is exactly the kind of thing that shouldn't be
 * guessed (see plan §"instrument before optimising").
 */
@Component
public class PiiRedactionWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(PiiRedactionWebFilter.class);
    private static final Set<String> FREE_TEXT_FIELDS = Set.of("rawText", "additionalText");

    private final ObjectMapper objectMapper;
    private final PiiRedactor redactor;
    private final MeterRegistry meterRegistry;

    public PiiRedactionWebFilter(ObjectMapper objectMapper, PiiRedactor redactor, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.redactor = redactor;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!appliesTo(request)) {
            return chain.filter(exchange);
        }

        return DataBufferUtils.join(request.getBody())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return redactBody(request.getPath().value(), bytes);
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(redactedBytes ->
                        chain.filter(exchange.mutate().request(decorate(request, exchange, redactedBytes)).build()));
    }

    private boolean appliesTo(ServerHttpRequest request) {
        if (request.getMethod() != HttpMethod.POST) {
            return false;
        }
        String path = request.getPath().value();
        return path.equals("/grievances")
                || path.equals("/grievances/workflow")
                || path.matches("/grievances/[^/]+/workflow/clarify");
    }

    private byte[] redactBody(String path, byte[] original) {
        try {
            JsonNode root = objectMapper.readTree(original);
            if (!(root instanceof ObjectNode obj)) {
                return original;
            }
            boolean changed = false;
            for (String field : FREE_TEXT_FIELDS) {
                JsonNode value = obj.get(field);
                if (value == null || !value.isString()) {
                    continue;
                }
                PiiRedactor.Result result = redactor.redact(value.asString());
                if (!result.redactedTypes().isEmpty()) {
                    obj.put(field, result.text());
                    changed = true;
                    log.warn("Redacted PII ({}) from '{}' on {} before storage/classification",
                            result.redactedTypes(), field, path);
                    for (String type : result.redactedTypes()) {
                        Counter.builder("aigre.guardrail.pii_redacted")
                                .tag("type", type)
                                .tag("field", field)
                                .register(meterRegistry)
                                .increment();
                    }
                }
            }
            return changed ? objectMapper.writeValueAsBytes(obj) : original;
        } catch (RuntimeException e) {
            // Malformed JSON isn't this filter's problem to solve -- let the original body reach
            // the controller so @Valid/@RequestBody produces its normal 400, instead of masking
            // it behind a guardrail failure.
            return original;
        }
    }

    private ServerHttpRequest decorate(ServerHttpRequest request, ServerWebExchange exchange, byte[] body) {
        return new ServerHttpRequestDecorator(request) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.setContentLength(body.length);
                return headers;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                // CLAUDE.md gotcha: Flux.just(dataBuffer) is single-use -- wrap in Flux.defer so a
                // fresh buffer is created per subscription instead of reusing one that may already
                // have been read/released.
                return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
            }
        };
    }
}
