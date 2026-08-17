package com.aigre.classification;

import com.aigre.metrics.LlmCallTimer;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
 * Reason before verdict: the model explains itself in prose, then emits a RESULT: marker
 * followed by a single-line JSON object. A manual reason+JSON-marker prompt was used instead of
 * langchain4j's AiServices structured-output feature because Ollama's ChatModel doesn't declare
 * RESPONSE_FORMAT_JSON_SCHEMA support by default (would need explicit per-provider capability
 * wiring to verify), and this project already has a proven version of this exact pattern in
 * RetrievalService's rerank call -- reusing it keeps the two LLM-call sites consistent and
 * avoids a second round of cross-provider structured-output verification.
 */
@Component
public class LlmGrievanceClassifier {

    private static final Pattern RESULT_PATTERN = Pattern.compile("RESULT:\\s*(\\{.*\\})", Pattern.DOTALL);

    private static final String PROMPT_TEMPLATE =
            """
            You are the intake classifier for a city government citizen grievance portal. Classify
            the complaint below.

            DEPARTMENTS (pick exactly one as your best guess, or null if none apply):
            %s

            PRIORITY RUBRIC (apply exactly, do not improvise):
            - CRITICAL: the text describes an active hazard -- gas leak/odor, exposed or downed
              electrical wiring, structural collapse risk, suspected child abuse/neglect,
              immediate risk of violence or a weapon, a completely dark traffic signal at a busy
              intersection, or an active water main break creating a road hazard.
            - HIGH: a service outage affecting many people (e.g. a water main break, multiple
              streetlights out on one block), OR impact on a vulnerable population (no heat/hot
              water, elder welfare concern with no immediate-danger language, an elevator outage
              affecting a mobility-impaired resident).
            - MEDIUM: an individual, non-hazardous service issue -- this is the default for most
              single, straightforward complaints.
            - LOW: cosmetic or administrative (faded paint, a general information request).
            Do NOT bump priority just because the citizen sounds angry or frustrated -- sentiment
            alone never changes priority tier in this system.

            ACTIONABILITY: set actionable=false (and department=null, priority=null) ONLY for: pure
            compliments with no actionable issue, spam/gibberish, federal or state government
            matters (taxes, SNAP, unemployment insurance), private civil disputes between
            residents (property lines, custody), or anything clearly outside city jurisdiction.
            A complaint that sounds like a real, plausible city problem but is too vague to
            pin down (e.g. "things are bad on my street", "someone should look into this
            neighborhood") is NOT one of those categories -- it is actionable=true with LOW
            confidence and department=null (see CONFIDENCE below), NOT actionable=false. Vagueness
            alone is never grounds for actionable=false; it is grounds for low confidence.

            CONFIDENCE: use a LOW confidence (below 0.5) when the text is too vague to identify a
            specific issue or department (e.g. "things are bad on my street", no actionable detail)
            -- do not force a guess into false confidence, and do not mark it not-actionable either.
            Use a confidence around 0.5-0.7 when the department is genuinely ambiguous between two
            plausible options. Use a HIGH confidence (0.8+) only when the department and issue are
            clearly identifiable.

            SENTIMENT: classify the citizen's tone as POSITIVE, NEUTRAL, or NEGATIVE, with a
            sentimentScore from -1.0 (very negative) to 1.0 (very positive).

            EXAMPLES (note every string value is double-quoted, including the enum-like ones --
            this matters, RESULT must be valid JSON):

            Complaint: "There's a big pile of old furniture and drywall dumped in the empty lot
            behind my house."
            Reasoning: unauthorized debris dumped on a lot by someone else is illegal dumping,
            DEP's jurisdiction, not a hazard and not affecting many people.
            RESULT: {"department": "DEP", "category": "illegal-dumping", "priority": "MEDIUM", \
            "confidence": 0.9, "sentimentLabel": "NEGATIVE", "sentimentScore": -0.4, \
            "actionable": true}

            Complaint: "There's exposed wiring sparking out of a downed pole after the storm."
            Reasoning: a downed pole with exposed wiring is DPW's street-lighting/electrical
            infrastructure -- DPW fixes it even though it's an environmental-sounding hazard.
            CRITICAL per the hazard rubric.
            RESULT: {"department": "DPW", "category": "street-lighting", "priority": "CRITICAL", \
            "confidence": 0.95, "sentimentLabel": "NEGATIVE", "sentimentScore": -0.6, \
            "actionable": true}

            Complaint: "The elevator in our public housing building has been out for two days and
            my neighbor uses a wheelchair."
            Reasoning: public housing maintenance (including elevators) is DHUD's jurisdiction,
            not DPW's -- DPW only manages non-housing public buildings like city hall. Mobility
            impact on a resident makes this HIGH, not MEDIUM.
            RESULT: {"department": "DHUD", "category": "public-housing", "priority": "HIGH", \
            "confidence": 0.9, "sentimentLabel": "NEGATIVE", "sentimentScore": -0.3, \
            "actionable": true}

            Complaint: "Things have been bad on my street lately and nobody seems to care."
            Reasoning: this sounds like a real, plausible city problem, but there is no specific
            issue or department identifiable -- it is actionable (do not mark actionable=false
            just because it's vague), just with low confidence and no department guess.
            RESULT: {"department": null, "category": null, "priority": null, \
            "confidence": 0.2, "sentimentLabel": "NEGATIVE", "sentimentScore": -0.5, \
            "actionable": true}

            Now classify the real complaint. Reason briefly, then on its own final line output
            exactly one line starting with RESULT: followed by a single-line JSON object with
            these exact fields, matching the field order and quoting style of the examples above.
            IMPORTANT: when department, category, or priority do not apply, write the bare JSON
            null with no quotes around it -- writing the quoted text "null" as a string is WRONG
            and will be treated as an invalid department code, not as "no department."

            Template (department/category/priority shown here as populated; use unquoted null
            for any of the three that don't apply):
            RESULT: {"department": "DOT", "category": "road-surface", "priority": "MEDIUM", \
            "confidence": 0.8, "sentimentLabel": "NEUTRAL", "sentimentScore": 0.0, \
            "actionable": true}

            Complaint: %s
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final LlmCallTimer llmCallTimer;
    private final DepartmentDirectory departmentDirectory;

    public LlmGrievanceClassifier(
            ChatModel chatModel,
            ObjectMapper objectMapper,
            LlmCallTimer llmCallTimer,
            DepartmentDirectory departmentDirectory) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.llmCallTimer = llmCallTimer;
        this.departmentDirectory = departmentDirectory;
    }

    public ClassificationResult classify(String rawText) {
        String prompt = PROMPT_TEMPLATE.formatted(departmentDirectory.departmentsPromptSection(), rawText);
        String response = llmCallTimer.time("classification", () -> chatModel.chat(prompt));
        return parse(response);
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
