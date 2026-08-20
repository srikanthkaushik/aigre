package com.aigre.guardrail;

import com.aigre.classification.ClassificationResult;
import com.aigre.classification.LlmGrievanceClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end check that PiiRedactionWebFilter actually runs on the real HTTP path -- a unit test
 * against PiiRedactor alone (see PiiRedactorTest) can't verify the filter is wired up and rewrites
 * the request body before it reaches the controller/DB. Classifier mocked so this doesn't depend
 * on live Ollama output; the thing under test is redaction, not classification.
 *
 * WebTestClient is built manually against @LocalServerPort rather than @Autowired -- Spring Boot
 * 4's WebTestClient auto-configuration context customizer isn't on this project's classpath
 * (modularized differently than 3.x; the usual spring-boot-starter-webflux-test starter doesn't
 * restore it), so autowiring it throws NoSuchBeanDefinitionException.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PiiRedactionWebFilterTest {

    @LocalServerPort
    private int port;

    private WebTestClient webClient;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @MockitoBean
    private LlmGrievanceClassifier classifier;

    @BeforeEach
    void setUp() {
        webClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void redactsSsnFromRawTextBeforeItsStored() {
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                "DHHS", "benefits", "MEDIUM", 0.8, "NEGATIVE", -0.2, true, "benefits case inquiry"));

        Map<String, Object> body = Map.of(
                "rawText", "My SSN is 123-45-6789 and I want to know why my benefits case is taking so long.");

        Map<String, Object> response = webClient.post()
                .uri("/grievances/workflow")
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        String grievanceId = (String) response.get("grievanceId");

        String storedRawText = jdbc.queryForObject(
                "SELECT raw_text FROM grievances WHERE id = :id",
                new MapSqlParameterSource("id", grievanceId),
                String.class);

        assertThat(storedRawText).doesNotContain("123-45-6789").contains("[REDACTED-SSN]");
    }

    @Test
    void leavesOrdinaryComplaintTextUntouched() {
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                "DOT", "road-surface", "MEDIUM", 0.8, "NEGATIVE", -0.1, true, "pothole report"));

        String original = "There's a large pothole on Maple Street that's been there for two weeks.";
        Map<String, Object> body = Map.of("rawText", original);

        Map<String, Object> response = webClient.post()
                .uri("/grievances/workflow")
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        String grievanceId = (String) response.get("grievanceId");

        String storedRawText = jdbc.queryForObject(
                "SELECT raw_text FROM grievances WHERE id = :id",
                new MapSqlParameterSource("id", grievanceId),
                String.class);

        assertThat(storedRawText).isEqualTo(original);
    }
}
