package com.aigre.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end against the real seeded employees (test-data/sql/seed.sql: priya.nakamura/AGENT/DOT,
 * marcus.webb/SUPERVISOR/DOT, lena.ortiz/AGENT/DPW, all sharing the demo password "Demo1234!") --
 * requires seed.sql to have been run against aigre-pg, same precondition as GrievanceMcpToolsTest.
 *
 * WebTestClient is built manually against @LocalServerPort rather than @Autowired -- see
 * PiiRedactionWebFilterTest's javadoc for why (Spring Boot 4's WebTestClient autoconfiguration
 * isn't on this project's classpath).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void loginWithValidCredentialsReturnsAToken() {
        LoginResponse response = client().post().uri("/auth/login")
                .bodyValue(Map.of("username", "priya.nakamura", "password", "Demo1234!"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("Priya Nakamura");
        assertThat(response.departmentId()).isEqualTo("DOT");
        assertThat(response.role()).isEqualTo("AGENT");
        assertThat(jwtService.parseToken(response.token())).isPresent();
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        client().post().uri("/auth/login")
                .bodyValue(Map.of("username", "priya.nakamura", "password", "wrong-password"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithUnknownUsernameIsRejected() {
        client().post().uri("/auth/login")
                .bodyValue(Map.of("username", "does.not.exist", "password", "Demo1234!"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void citizenSubmissionNeedsNoAuthentication() {
        client().post().uri("/grievances")
                .bodyValue(Map.of("rawText", "Public endpoint smoke test -- no Authorization header sent."))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void employeeListRequiresAuthentication() {
        client().get().uri("/grievances")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void employeeListSucceedsWithAValidToken() {
        String token = login("priya.nakamura");

        client().get().uri("/grievances")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void agentCannotMarkAGrievanceResolved() {
        String agentToken = login("priya.nakamura");

        client().post().uri("/grievances/a0000000-0000-0000-0000-000000000001/status")
                .header("Authorization", "Bearer " + agentToken)
                .bodyValue(Map.of("newStatus", "IN_PROGRESS", "note", "should be forbidden", "changedBy", "priya.nakamura"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void supervisorCannotActOnAnotherDepartmentsGrievance() {
        // marcus.webb is DOT; a0000000-...-000000000003 is a DPW grievance (seed.sql).
        String dotSupervisorToken = login("marcus.webb");

        client().post().uri("/grievances/a0000000-0000-0000-0000-000000000003/status")
                .header("Authorization", "Bearer " + dotSupervisorToken)
                .bodyValue(Map.of("newStatus", "IN_PROGRESS", "note", "cross-department, should be forbidden",
                        "changedBy", "marcus.webb"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void trendsEndpointIsNotAccidentallyPublicViaTheGrievanceIdPattern() {
        // Regression guard: pathMatchers(GET, "/grievances/{id}") is permitAll for the citizen
        // status lookup, and "{id}" matches any single path segment -- including the literal
        // "trends" -- unless SecurityConfig explicitly protects it first.
        client().get().uri("/grievances/trends")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String login(String username) {
        LoginResponse response = client().post().uri("/auth/login")
                .bodyValue(Map.of("username", username, "password", "Demo1234!"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(response).isNotNull();
        return response.token();
    }
}
