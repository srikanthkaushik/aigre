package com.aigre.email;

import com.aigre.classification.ClassificationResult;
import com.aigre.classification.LlmGrievanceClassifier;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end test of the email ingestion channel against an embedded fake mailbox (GreenMail) --
 * no live SMTP/IMAP infra required. Mocks LlmGrievanceClassifier for deterministic routing, same
 * rationale as GrievanceWorkflowPauseResumeTest. Ports come from ServerSetupTest's fixed test
 * constants (not runtime-negotiated), so they're known before the GreenMail server actually
 * starts -- safe to hand to @DynamicPropertySource, which resolves before the Spring context
 * (and this poller's @Value fields) are built.
 */
@SpringBootTest
class EmailGrievancePollerTest {

    private static final String MAILBOX = "citizen-intake@aigre.test";
    private static final String MAILBOX_PASSWORD = "test-password";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    @DynamicPropertySource
    static void emailProperties(DynamicPropertyRegistry registry) {
        registry.add("email.enabled", () -> "true");
        registry.add("email.imap.host", () -> "localhost");
        registry.add("email.imap.port", () -> ServerSetupTest.IMAP.getPort());
        registry.add("email.imap.protocol", () -> "imap");
        registry.add("email.imap.username", () -> MAILBOX);
        registry.add("email.imap.password", () -> MAILBOX_PASSWORD);
        // Far longer than this test class ever runs -- poll() is invoked manually and
        // deterministically below; without this, the app's own real @Scheduled trigger (default
        // 60s) can fire in the background against the same live Spring context and race a manual
        // poll() call, double-ingesting a message before either call marks it SEEN.
        registry.add("email.poll-interval-ms", () -> "3600000");
    }

    @Autowired
    private EmailGrievancePoller poller;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @MockitoBean
    private LlmGrievanceClassifier classifier;

    @BeforeEach
    void registerMailbox() {
        greenMail.setUser(MAILBOX, MAILBOX, MAILBOX_PASSWORD);
    }

    @Test
    void unseenEmailBecomesAGrievanceThroughTheSameWorkflowAsThePortal() throws Exception {
        String category = "test-cat-" + UUID.randomUUID();
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                "DOT", category, "MEDIUM", 0.9, "NEGATIVE", -0.4, true,
                "Confident pothole classification"));

        GreenMailUtil.sendTextEmail(
                MAILBOX,
                "jane.citizen@example.com",
                "Pothole on Maple Street",
                "There's a large pothole outside my house. Call me at 555-123-4567.",
                ServerSetupTest.SMTP);

        poller.poll();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT g.channel, g.status, g.raw_text, c.email AS citizen_email "
                        + "FROM grievances g JOIN citizens c ON c.id = g.citizen_id "
                        + "WHERE g.category = :category",
                new MapSqlParameterSource("category", category));

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("channel")).isEqualTo("EMAIL");
        assertThat(row.get("citizen_email")).isEqualTo("jane.citizen@example.com");
        assertThat((String) row.get("raw_text")).contains("Subject: Pothole on Maple Street");
        assertThat((String) row.get("raw_text")).contains("[REDACTED-PHONE]");
        assertThat((String) row.get("raw_text")).doesNotContain("555-123-4567");
    }

    @Test
    void secondPollFindsNothingNewOnceTheMessageIsMarkedSeen() throws Exception {
        when(classifier.classify(anyString())).thenReturn(ClassificationResult.unparseable("n/a"));

        // A unique marker per run, not a fixed literal -- this test doesn't clean up its own rows
        // (matching GrievanceWorkflowPauseResumeTest's established convention in this suite), so a
        // fixed body string would accumulate false matches from every prior run against this same
        // persistent dev database and make the exact-count assertions below flaky.
        String marker = "streetlight-" + UUID.randomUUID();
        GreenMailUtil.sendTextEmail(
                MAILBOX,
                "second.citizen@example.com",
                "Broken streetlight " + marker,
                "The streetlight outside 42 Elm St has been out for a week.",
                ServerSetupTest.SMTP);
        String likePattern = "%" + marker + "%";

        poller.poll();
        long afterFirstPoll = jdbc.queryForObject(
                "SELECT count(*) FROM grievances WHERE channel = 'EMAIL' AND raw_text LIKE :pattern",
                new MapSqlParameterSource("pattern", likePattern), Long.class);
        assertThat(afterFirstPoll).isEqualTo(1);

        poller.poll();
        long afterSecondPoll = jdbc.queryForObject(
                "SELECT count(*) FROM grievances WHERE channel = 'EMAIL' AND raw_text LIKE :pattern",
                new MapSqlParameterSource("pattern", likePattern), Long.class);
        assertThat(afterSecondPoll).isEqualTo(1);
    }
}
