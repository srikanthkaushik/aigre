package com.aigre.chat;

import com.aigre.intake.GrievanceIntakeRequest;
import com.aigre.intake.GrievanceIntakeResponse;
import com.aigre.intake.GrievanceIntakeService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live end-to-end test against real Postgres, live Ollama, and the real self-referential MCP
 * client (McpClientConfig/LazyGrievanceToolProvider) -- not mocked, since the thing under test is
 * genuine LLM tool-calling: does the citizen chat actually reach for get_grievance_status when a
 * question references a real grievance, and leave the RAG-only path unchanged otherwise. See
 * PROJECT.md's "MCP tool wiring" section for the manual curl verification this automates.
 *
 * Tool-call triggering depends on live model behavior -- if statusQuestionAboutARealGrievance
 * TriggersALiveToolCall flakes, that's the same class of live-LLM variance ComplaintEvalHarness
 * Test already documents, not necessarily a wiring regression; re-run before assuming a bug.
 *
 * WebTestClient built manually against @LocalServerPort -- same reason as
 * PiiRedactionWebFilterTest: Spring Boot 4's WebTestClient auto-configuration isn't on this
 * project's classpath.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatControllerTest {

    @LocalServerPort
    private int port;

    private WebTestClient webClient;

    @Autowired
    private GrievanceIntakeService intakeService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ToolProvider grievanceReadOnlyToolProvider;

    @BeforeEach
    void setUp() {
        webClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(90))
                .build();
    }

    /**
     * Deterministic, no LLM involved -- exercises the actual filterToolNames wiring rather than
     * hoping a prompt never talks the model into the wrong tool. This is the load-bearing safety
     * mechanism stopping the citizen chat from ever reaching update_grievance_status/
     * reopen_grievance (see McpClientConfig's javadoc).
     */
    @Test
    void onlyReadOnlyToolsAreEverExposedToTheCitizenChatModel() {
        ToolProviderRequest request = new ToolProviderRequest("test-chat-memory", UserMessage.from("test"));

        Set<String> exposedToolNames = grievanceReadOnlyToolProvider.provideTools(request).tools().keySet().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toSet());

        assertThat(exposedToolNames)
                .containsExactlyInAnyOrder("get_grievance_status", "check_sla_status", "find_duplicate_chain");
    }

    @Test
    void ragOnlyQuestionNeverTriggersAToolCall() {
        double before = toolCallCount("get_grievance_status");

        List<ServerSentEvent<String>> events = streamChat(
                "How long does DOT have to repair a reported pothole once it's submitted?");

        assertThat(events).anyMatch(e -> "sources".equals(e.event()));
        assertThat(toolCallCount("get_grievance_status")).isEqualTo(before);
    }

    @Test
    void statusQuestionAboutARealGrievanceTriggersALiveToolCall() {
        GrievanceIntakeResponse submitted = intakeService.submit(new GrievanceIntakeRequest(
                "There's a large pothole on Oak Street that's been there for three weeks and is "
                        + "damaging car tires.",
                null, null, null));

        double before = toolCallCount("get_grievance_status");

        List<ServerSentEvent<String>> events =
                streamChat("What is the status of grievance " + submitted.id() + "?");

        assertThat(events).anyMatch(e -> "sources".equals(e.event()));
        assertThat(toolCallCount("get_grievance_status")).isEqualTo(before + 1);
    }

    /**
     * Repeats the same RAG-only question (never grievance-ID shaped, so a tool call never fires --
     * same guarantee ragOnlyQuestionNeverTriggersAToolCall relies on) and expects the second call
     * to be served from ChatResponseCache instead of re-running retrieval + the chat LLM call.
     * Uses phrasing distinct from every other test's question text so cache state from another
     * test (which may run first, in any order) can't produce a stray hit here.
     */
    @Test
    void repeatingTheExactSameAnonymousQuestionIsServedFromCacheOnTheSecondCall() {
        String question = "How long does DOT have to repair a reported pothole once it's submitted, per the cache test?";

        double missBefore = cacheResultCount("miss");
        double hitBefore = cacheResultCount("hit");

        streamChat(question);
        assertThat(cacheResultCount("miss")).isEqualTo(missBefore + 1);
        assertThat(cacheResultCount("hit")).isEqualTo(hitBefore);

        List<ServerSentEvent<String>> secondCall = streamChat(question);
        assertThat(cacheResultCount("hit")).isEqualTo(hitBefore + 1);
        assertThat(cacheResultCount("miss")).isEqualTo(missBefore + 1);
        assertThat(secondCall).anyMatch(e -> "token".equals(e.event()));
        assertThat(secondCall).anyMatch(e -> "sources".equals(e.event()));
    }

    /**
     * A citizen recognized via citizenToken must never read from or write to the shared cache,
     * even asking the identical question twice -- see ChatController's class javadoc for why
     * (the answer may reflect that citizen's own live grievance data).
     */
    @Test
    void personalizedQuestionsAlwaysBypassTheCacheEvenWhenRepeated() {
        GrievanceIntakeResponse submitted = intakeService.submit(new GrievanceIntakeRequest(
                "There's a broken streetlight on Elm Street that's been out for two weeks.",
                "Cache Test Citizen", "cache-test-citizen@example.com", null));
        assertThat(submitted.citizenToken()).isNotNull();

        String question = "What is the status of my most recent complaint, for the cache bypass test?";

        double bypassBefore = cacheResultCount("bypass");
        double hitBefore = cacheResultCount("hit");

        streamChat(question, submitted.citizenToken());
        streamChat(question, submitted.citizenToken());

        assertThat(cacheResultCount("bypass")).isEqualTo(bypassBefore + 2);
        assertThat(cacheResultCount("hit")).isEqualTo(hitBefore);
    }

    /**
     * Tool-triggering answers reflect live, mutable per-grievance state, so they must never be
     * cached regardless of repetition. Live-LLM tool-call triggering is the same class of
     * variance statusQuestionAboutARealGrievanceTriggersALiveToolCall already documents -- re-run
     * before assuming a regression if this flakes.
     */
    @Test
    void toolTriggeringAnswersAreNeverCachedEvenWhenTheSameQuestionRepeats() {
        GrievanceIntakeResponse submitted = intakeService.submit(new GrievanceIntakeRequest(
                "There's a large pothole on Maple Street that's been there for three weeks.",
                null, null, null));
        String question = "What is the status of grievance " + submitted.id() + "?";

        double missBefore = cacheResultCount("miss");
        double hitBefore = cacheResultCount("hit");

        streamChat(question);
        streamChat(question);

        assertThat(cacheResultCount("hit")).isEqualTo(hitBefore);
        assertThat(cacheResultCount("miss")).isEqualTo(missBefore + 2);
    }

    private double cacheResultCount(String result) {
        var counter = meterRegistry.find("aigre.chat.cache").tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double toolCallCount(String toolName) {
        var counter = meterRegistry.find("aigre.chat.tool_calls").tag("tool", toolName).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private List<ServerSentEvent<String>> streamChat(String question) {
        return streamChat(Map.of("question", question));
    }

    private List<ServerSentEvent<String>> streamChat(String question, String citizenToken) {
        return streamChat(Map.of("question", question, "citizenToken", citizenToken));
    }

    private List<ServerSentEvent<String>> streamChat(Map<String, String> body) {
        return webClient.post()
                .uri("/chat/stream")
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(90));
    }
}
