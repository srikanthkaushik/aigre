package com.aigre.auth;

import com.aigre.department.DepartmentSummary;
import com.aigre.query.GrievanceSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dmv profile's counterpart to SecurityIntegrationTest: login, bureau scoping and role gating
 * against the real seeded DMV employees (test-data/dmv/sql/seed.sql -- erin.callahan/AGENT/REG,
 * victor.osei/SUPERVISOR/REG, priscilla.moreau/AGENT/TITLE, dev.raman/SUPERVISOR/TITLE, ops.admin,
 * all sharing "Demo1234!"), plus the profile's public wiring (/app-config, /departments).
 *
 * Seed grievances used: G0001 (REG, IN_PROGRESS), G0003 (FR, ROUTED), G0007 (DEALER, IN_PROGRESS).
 * The successful status changes re-set IN_PROGRESS on rows already IN_PROGRESS, so repeat runs add
 * status_history rows but don't move the demo data.
 *
 * Only runs under the dmv profile, against the seeded DMV database:
 *   mvn test -Dtest=DmvSecurityIntegrationTest -Dspring.profiles.active=dmv
 *     -Dspring.datasource.url=jdbc:postgresql://localhost:5434/aigre_dmv
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "spring.profiles.active", matches = "dmv")
class DmvSecurityIntegrationTest {

    @LocalServerPort
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    void appConfigServesTheDmvWording() {
        client().get().uri("/app-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.agencyName").isEqualTo("NH Division of Motor Vehicles")
                .jsonPath("$.unitLabel").isEqualTo("Bureau")
                .jsonPath("$.agencyWideEmbed").isEqualTo(true);
    }

    @Test
    void departmentsAreTheFiveBureaus() {
        List<DepartmentSummary> departments = client().get().uri("/departments")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(DepartmentSummary.class)
                .returnResult()
                .getResponseBody();

        assertThat(departments).extracting(DepartmentSummary::id)
                .containsExactlyInAnyOrder("REG", "DL", "FR", "TITLE", "DEALER");
    }

    @Test
    void bureauAgentLoginCarriesTheirBureau() {
        LoginResponse response = loginResponse("erin.callahan");

        assertThat(response.name()).isEqualTo("Erin Callahan");
        assertThat(response.departmentId()).isEqualTo("REG");
        assertThat(response.role()).isEqualTo("AGENT");
    }

    @Test
    void bureauQueueOnlyShowsThatBureausGrievances() {
        String token = login("priscilla.moreau");

        List<GrievanceSummary> queue = client().get().uri("/grievances")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(GrievanceSummary.class)
                .returnResult()
                .getResponseBody();

        assertThat(queue).isNotEmpty();
        assertThat(queue).extracting(GrievanceSummary::department).containsOnly("TITLE");
    }

    @Test
    void agentCannotChangeStatusEvenInTheirOwnBureau() {
        String agentToken = login("erin.callahan");

        client().post().uri("/grievances/G0001/status")
                .header("Authorization", "Bearer " + agentToken)
                .bodyValue(Map.of("newStatus", "IN_PROGRESS", "note", "should be forbidden", "changedBy", "erin.callahan"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void supervisorCanActWithinTheirOwnBureau() {
        String regSupervisorToken = login("victor.osei");

        client().post().uri("/grievances/G0001/status")
                .header("Authorization", "Bearer " + regSupervisorToken)
                .bodyValue(Map.of("newStatus", "IN_PROGRESS", "note", "own-bureau action", "changedBy", "victor.osei"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void supervisorCannotActOnAnotherBureausGrievance() {
        // dev.raman is TITLE; G0003 is an FR grievance.
        String titleSupervisorToken = login("dev.raman");

        client().post().uri("/grievances/G0003/status")
                .header("Authorization", "Bearer " + titleSupervisorToken)
                .bodyValue(Map.of("newStatus", "IN_PROGRESS", "note", "cross-bureau, should be forbidden",
                        "changedBy", "dev.raman"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanActOnAnyBureausGrievance() {
        String adminToken = login("ops.admin");

        client().post().uri("/grievances/G0007/status")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of("newStatus", "IN_PROGRESS", "note", "admin cross-bureau action", "changedBy", "ops.admin"))
                .exchange()
                .expectStatus().isOk();
    }

    private String login(String username) {
        return loginResponse(username).token();
    }

    private LoginResponse loginResponse(String username) {
        LoginResponse response = client().post().uri("/auth/login")
                .bodyValue(Map.of("username", username, "password", "Demo1234!"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }
}
