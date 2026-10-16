package com.soubhagya.systivex.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.soubhagya.systivex.observation.TwinSyncService;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

/**
 * Endpoint authorization over real HTTP with explicit bearer tokens: role
 * matrix per endpoint, TWIN_ADMIN inheritance, loopback guards staying
 * authoritative behind valid operator tokens, actuator policy, deny-by-default,
 * and preserved 400/404/409 contracts plus read-only advisory behaviour.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.endpoints.web.exposure.include=health,prometheus",
            "management.endpoint.health.show-details=never"
        })
@AutoConfigureMockMvc
class SecurityAuthorizationApiTest extends AbstractPostgresIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private MockMvc mockMvc;
    @Autowired private TwinSyncService syncService;

    private final RestTemplate http = new RestTemplate();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
        return http.exchange(url(path), method, new HttpEntity<>(body, bearer(token)),
                String.class);
    }

    private static String token(String... roles) {
        return TestJwt.token(List.of(roles));
    }

    @BeforeEach
    void syncGraph() {
        http.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response)
                    throws java.io.IOException {
                return false;
            }
        });
        Path root = Path.of("").toAbsolutePath().resolve("target-services");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(root), "real target-services tree not present");
        syncService.synchronize(root);
    }

    @Test
    void twinReadsNeedRead() {
        assertThat(call(HttpMethod.GET, "/api/v1/twin/entities", token("TWIN_READ"), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/relationships", token("TWIN_READ"), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/entities", token("TWIN_ANALYZE"), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void twinPostsNeedAdmin() {
        Map<String, Object> body = Map.of("type", "SERVICE", "name",
                "sec-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(call(HttpMethod.POST, "/api/v1/twin/entities", token("TWIN_READ"), body)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/entities", token("TWIN_ADMIN"), body)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void impactAndRiskNeedRead() {
        String from = "target-service:order-service";
        assertThat(call(HttpMethod.GET, "/api/v1/twin/impact?from=" + from,
                        token("TWIN_READ"), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/risk?from=" + from,
                        token("TWIN_READ"), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/impact?from=" + from,
                        token("TWIN_OPERATOR"), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void simulationsAndPolicyNeedAnalyze() {
        Map<String, Object> body = Map.of("from",
                List.of("target-service:order-service"), "operations", List.of());
        assertThat(call(HttpMethod.POST, "/api/v1/twin/simulations",
                        token("TWIN_ANALYZE"), body).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/policy-evaluations",
                        token("TWIN_ANALYZE"), body).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/simulations",
                        token("TWIN_READ"), body).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void syncAndObservationsNeedOperatorOverLoopback() {
        assertThat(call(HttpMethod.POST, "/api/v1/twin/sync", token("TWIN_OPERATOR"),
                        Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/observations", token("TWIN_OPERATOR"),
                        Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/sync", token("TWIN_READ"), Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminInheritsReadAnalyzeAndOperator() {
        assertThat(call(HttpMethod.GET, "/api/v1/twin/entities", token("TWIN_ADMIN"), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> body = Map.of("from",
                List.of("target-service:order-service"), "operations", List.of());
        assertThat(call(HttpMethod.POST, "/api/v1/twin/simulations", token("TWIN_ADMIN"), body)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/observations", token("TWIN_ADMIN"),
                        Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void operatorTokenDoesNotBypassLoopback() throws Exception {
        String operator = token("TWIN_OPERATOR");
        mockMvc.perform(post("/api/v1/twin/sync").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + operator).content("{}")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        }))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/twin/observations").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + operator).content("{}")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    @Test
    void forwardingHeadersDoNotBypassLoopback() throws Exception {
        String operator = token("TWIN_OPERATOR");
        for (String path : List.of("/api/v1/twin/sync", "/api/v1/twin/observations")) {
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + operator)
                            .header("X-Forwarded-For", "127.0.0.1")
                            .content("{}")
                            .with(request -> {
                                request.setRemoteAddr("203.0.113.9");
                                return request;
                            }))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + operator)
                            .header("Forwarded", "for=127.0.0.1")
                            .content("{}")
                            .with(request -> {
                                request.setRemoteAddr("203.0.113.9");
                                return request;
                            }))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void actuatorAndDenyByDefault() {
        assertThat(call(HttpMethod.GET, "/actuator/health", null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> health = call(HttpMethod.GET, "/actuator/health", null, null);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
        assertThat(health.getBody()).doesNotContain("password", "secret", "credential");
        // Anonymous access to metrics is rejected at the security layer.
        assertThat(call(HttpMethod.GET, "/actuator/prometheus", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // Boot's test support disables the Prometheus scrape endpoint, so this
        // slice cannot prove a live 200 metrics response. A TWIN_READ response
        // other than 401/403 only proves the request passed authentication and
        // the READ role check and then reached the (absent) actuator endpoint
        // (404 here, 200 live). Live metrics delivery remains unverified here.
        org.springframework.http.HttpStatusCode prometheusWithRead = call(
                        HttpMethod.GET, "/actuator/prometheus", token("TWIN_READ"), null)
                .getStatusCode();
        assertThat(prometheusWithRead).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(prometheusWithRead).isNotEqualTo(HttpStatus.FORBIDDEN);
        // Authenticated identities without TWIN_READ are denied at the
        // security layer, regardless of endpoint availability.
        assertThat(call(HttpMethod.GET, "/actuator/prometheus", token("SUPERUSER"), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/actuator/prometheus", token("TWIN_ANALYZE"), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/actuator/env", token("TWIN_ADMIN"), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/nope", token("TWIN_ADMIN"), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void errorContractsAndAdvisoryWordingPreserved() {
        Map<String, Object> blank = Map.of("type", "SERVICE", "name", "   ");
        assertThat(call(HttpMethod.POST, "/api/v1/twin/entities", token("TWIN_ADMIN"), blank)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/entities/" + UUID.randomUUID(),
                        token("TWIN_ADMIN"), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = Map.of("type", "SERVICE", "name",
                "dup-" + UUID.randomUUID().toString().substring(0, 8),
                "externalRef", "sec:dupe");
        assertThat(call(HttpMethod.POST, "/api/v1/twin/entities", token("TWIN_ADMIN"), body)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/entities", token("TWIN_ADMIN"), body)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> simulated = call(HttpMethod.POST, "/api/v1/twin/simulations",
                token("TWIN_ANALYZE"), Map.of("from",
                        List.of("target-service:order-service"), "operations", List.of()));
        assertThat(simulated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(simulated.getBody()).contains("SIMULATED");
        ResponseEntity<String> advisory = call(HttpMethod.POST,
                "/api/v1/twin/policy-evaluations", token("TWIN_ANALYZE"), Map.of("from",
                        List.of("target-service:order-service"), "operations", List.of()));
        assertThat(advisory.getBody()).contains("NOT AN AUTHORIZATION");
    }

    @Test
    void decisionEndpointsNeedExplicitApprover() {
        String ghost = "/api/v1/twin/proposals/" + UUID.randomUUID() + "/approve";
        Map<String, Object> reason = Map.of("reason", "matrix");
        assertThat(call(HttpMethod.POST, ghost, null, reason).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST, ghost, token("TWIN_READ"), reason).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, ghost, token("TWIN_ANALYZE"), reason).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, ghost, token("TWIN_ADMIN"), reason).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, ghost, token("TWIN_APPROVER"), reason).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
