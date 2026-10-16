package com.aigre.classification;

import com.aigre.metrics.LlmCallTimer;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real LLM-based classification, replacing the day-one PlaceholderClassifier keyword stub.
 * Determines department, category, priority (plan §1.5 rubric), confidence, and sentiment in
 * one call.
 *
 * Multi-department cases (routing scenario 2) are deliberately NOT auto-resolved here: per plan
 * milestone 4, genuine department ambiguity is meant to pause for human review, not be guessed
 * away. This classifier signals ambiguity via lower confidence on its single best-guess
 * department rather than attempting to output multiple departments -- which also means no schema
 * change was needed to land this.
 *
 * The prompt itself lives in a classpath resource chosen by aigre.domain.classifier-prompt
 * (prompts/city/classifier.txt by default, prompts/dmv/classifier.txt under the dmv profile),
 * since its framing, priority rubric and examples are specific to the agency being served.
 *
 * Reason before verdict: the model explains itself in prose, then emits a RESULT: marker
 * followed by a single-line JSON object. A manual reason+JSON-marker prompt was used instead of
 * langchain4j's AiServices structured-output feature because Ollama's ChatModel doesn't declare
 * RESPONSE_FORMAT_JSON_SCHEMA support by default (would need explicit per-provider capability
 * wiring to verify), and this project already has a proven version of this exact pattern in
 * RetrievalService's rerank call -- reusing it keeps the two LLM-call sites consistent and
 * avoids a second round of cross-provider structured-output verification.
 *
 * Injects the "classificationChatModel"-qualified bean (LlmProviderConfig), not the
 * @Primary general one RetrievalService's rerank step uses -- classification runs once per
 * grievance, so it can afford a slower, more-accurate model (qwen3:8b under Ollama) without
 * the RAG-rerank latency multiplication that made a single shared model a bad tradeoff.
 */
@Component
public class LlmGrievanceClassifier {

    private static final Pattern RESULT_PATTERN = Pattern.compile("RESULT:\\s*(\\{.*\\})", Pattern.DOTALL);

    private final String promptTemplate;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final LlmCallTimer llmCallTimer;
    private final DepartmentDirectory departmentDirectory;

    public LlmGrievanceClassifier(
            @Qualifier("classificationChatModel") ChatModel chatModel,
            ObjectMapper objectMapper,
            LlmCallTimer llmCallTimer,
            DepartmentDirectory departmentDirectory,
            @Value("${aigre.domain.classifier-prompt:classpath:prompts/city/classifier.txt}") Resource promptResource) {
        this.promptTemplate = loadPromptTemplate(promptResource);
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.llmCallTimer = llmCallTimer;
        this.departmentDirectory = departmentDirectory;
    }

    public ClassificationResult classify(String rawText) {
        String prompt = promptTemplate.formatted(departmentDirectory.departmentsPromptSection(), rawText);
        String response = llmCallTimer.time("classification", () -> chatModel.chat(prompt));
        return parse(response);
    }

    /**
     * Reads the agency-specific prompt (framing, priority rubric, actionability rules, few-shot
     * examples) for the active domain profile. Two %s slots: the live DEPARTMENTS section, then
     * the complaint. Line endings are normalized because git on Windows may check the file out
     * with CRLF, which would otherwise silently change the prompt text.
     */
    static String loadPromptTemplate(Resource resource) {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read classifier prompt " + resource, e);
        }
    }

    private ClassificationResult parse(String response) {
        Matcher matcher = RESULT_PATTERN.matcher(response);
        if (!matcher.find()) {
            return ClassificationResult.unparseable(response);
        }
        try {
            JsonNode node = objectMapper.readTree(sanitizeUnquotedEnumValues(matcher.group(1)));
            return new ClassificationResult(
                    nullableText(node, "department"),
                    nullableText(node, "category"),
                    nullableText(node, "priority"),
                    node.path("confidence").asDouble(-1.0),
                    node.path("sentimentLabel").asString("NEUTRAL"),
                    node.path("sentimentScore").asDouble(0.0),
                    node.path("actionable").asBoolean(false),
                    response.substring(0, matcher.start()).trim());
        } catch (RuntimeException e) {
            return ClassificationResult.unparseable(response);
        }
    }

    /**
     * Treats the JSON string "null" (any case) the same as the JSON null literal -- smaller
     * models sometimes quote it as a string despite prompt instructions not to. Confirmed via
     * direct harness debugging: a case scored as a false mismatch because departmentPredicted()
     * held the four-character string "null", not an actual null reference.
     */
    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asString();
        return "null".equalsIgnoreCase(text) ? null : text;
    }

    /**
     * Defense-in-depth: smaller models occasionally emit unquoted enum-like values (e.g.
     * {@code "priority": LOW} instead of {@code "priority": "LOW"}), which is invalid JSON.
     * Quotes bare-word values (anything starting with a letter, not true/false/null) before
     * parsing rather than discarding an otherwise-good classification over a formatting slip.
     */
    private static String sanitizeUnquotedEnumValues(String json) {
        return json.replaceAll("(:\\s*)(?!true\\b|false\\b|null\\b)([A-Za-z][A-Za-z0-9_-]*)(\\s*[,}])", "$1\"$2\"$3");
    }
}
