package com.aigre.config;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.time.Duration;

/**
 * Connects to AIGRE's own MCP server lazily, on first real tool-provider invocation, rather than
 * at Spring bean-construction time. An eager connect-at-boot deterministically loses the race
 * against this app's own Netty listener -- McpServerAutoConfiguration's routes aren't live yet
 * while beans are still being constructed -- so eager-connect-with-catch here would silently and
 * *permanently* disable tool-calling on every cold start, not just occasionally as the pattern
 * intends for a genuinely-external, possibly-down agency MCP server. By the time the first
 * citizen chat request arrives, the app is guaranteed fully up.
 *
 * The URL itself is also resolved lazily, not injected at bean-construction time -- Spring Boot
 * only publishes the actually-bound port as local.server.port once the embedded server has
 * finished starting (via WebServerInitializedEvent), which is later than @Value resolution during
 * bean construction. This matters for @SpringBootTest(webEnvironment = RANDOM_PORT): a
 * bean-construction-time @Value would resolve against the fixed application.yml server.port
 * (8085), not the random port the test server actually bound -- found via ChatControllerTest
 * initially connecting to the wrong port and getting zero tools back. Falls back to server.port
 * for real deployment, where local.server.port is never set.
 *
 * A failed connection attempt is never cached -- only a successful one is -- so a transient
 * failure on the very first request still gets retried on the next one, rather than sticking on
 * RAG-only for the rest of the process lifetime.
 */
class LazyGrievanceToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(LazyGrievanceToolProvider.class);

    private final Environment environment;
    private volatile ToolProvider delegate;

    LazyGrievanceToolProvider(Environment environment) {
        this.environment = environment;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        return resolveDelegate().provideTools(request);
    }

    private ToolProvider resolveDelegate() {
        ToolProvider current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (delegate != null) {
                return delegate;
            }
            ToolProvider connected = tryConnect();
            if (connected != null) {
                delegate = connected;
                return delegate;
            }
            return request -> ToolProviderResult.builder().build();
        }
    }

    private ToolProvider tryConnect() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8085"));
        String mcpUrl = "http://localhost:" + port + "/mcp";
        try {
            McpTransport transport = StreamableHttpMcpTransport.builder()
                    .url(mcpUrl)
                    .timeout(Duration.ofSeconds(10))
                    .build();
            McpClient client = DefaultMcpClient.builder()
                    .transport(transport)
                    .clientName("aigre-chat")
                    .clientVersion("0.1.0")
                    .build();
            log.info("Connected to AIGRE's own MCP server at {} for citizen chat tool-calling.", mcpUrl);
            return McpToolProvider.builder()
                    .mcpClients(client)
                    .filterToolNames("get_grievance_status", "check_sla_status", "find_duplicate_chain")
                    .build();
        } catch (RuntimeException e) {
            log.warn(
                    "AIGRE's own MCP server not reachable at {} -- citizen chat tool-calling disabled for this "
                            + "request, RAG-only fallback stays active. Will retry on the next request.",
                    mcpUrl,
                    e);
            return null;
        }
    }
}
