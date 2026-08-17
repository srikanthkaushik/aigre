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

    private double toolCallCount(String toolName) {
        var counter = meterRegistry.find("aigre.chat.tool_calls").tag("tool", toolName).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private List<ServerSentEvent<String>> streamChat(String question) {
        return webClient.post()
                .uri("/chat/stream")
                .bodyValue(Map.of("question", question))
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(90));
    }
}
