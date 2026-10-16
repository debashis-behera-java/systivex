package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.security.TestJwt;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 11 S4: submission guards and amendments over real HTTP.
 * Fixtures are namespaced per test; governance tables, head row, and
 * twin rows are cleaned around each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ProposalSubmitAmendApiTest extends AbstractPostgresIntegrationTest {

    private static final tools.jackson.databind.ObjectMapper JSON =
            JsonMapper.builder().build();

    @LocalServerPort private int port;

    @Autowired private MockMvc mockMvc;

    @Autowired private JdbcTemplate jdbc;

    private final RestTemplate http = new RestTemplate();

    private String prefix;
    private String refB;
    private String refC;

    @BeforeEach
    void setup() {
        http.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response)
                    throws java.io.IOException {
                return false;
            }
        });
        prefix = "sec-s4s-" + UUID.randomUUID().toString().substring(0, 8);
        refB = prefix + ":svc-b";
        refC = prefix + ":svc-c";
        insertEntity("Beta", refB);
        insertEntity("Gamma", refC);
        resetGovernance();
    }

    @AfterEach
    void clean() {
        resetGovernance();
        jdbc.update("DELETE FROM system_entity WHERE external_ref LIKE '" + prefix + ":%'");
    }

    private void resetGovernance() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM proposal_revision");
        jdbc.update("UPDATE audit_chain_head SET last_seq = 0, last_hash = 'GENESIS'");
    }

    private void insertEntity(String name, String ref) {
        jdbc.update(
                "INSERT INTO system_entity (id, type, name, external_ref, environment, metadata)"
                        + " VALUES (?, 'SERVICE', ?, ?, 'test', '{}')",
                UUID.randomUUID(), name, ref);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }

    private static String token(String... roles) {
        return TestJwt.token(List.of(roles));
    }

    private static JsonNode body(ResponseEntity<String> response) {
        try {
            return JSON.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * PATCH goes through MockMvc: the default RestTemplate request
     * factory cannot send PATCH. The full security filter chain,
     * including JWT authentication, still applies.
     */
    private org.springframework.mock.web.MockHttpServletResponse patch(
            String path, String token, Object body) throws Exception {
        var request = MockMvcRequestBuilders.patch(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private String draft(String token, List<Map<String, Object>> ops) {
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/v1/twin/proposals", token,
                Map.of("title", "Change", "operations", ops));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return body(response).get("proposalKey").asText();
    }

    private List<Map<String, Object>> removeGamma() {
        return List.of(Map.of("type", "REMOVE_ENTITY", "entity", refC));
    }

    private JsonNode assess(String key, String token) {
        ResponseEntity<String> response =
                call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/assess", token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return body(response);
    }

    private Map<String, Object> submitBody(JsonNode assessed) {
        return Map.of(
                "revisionNumber", assessed.get("revisionNumber").asInt(),
                "operationsHash", assessed.get("operationsHash").asText(),
                "assessmentHash", assessed.get("assessmentHash").asText());
    }

    @Test
    void submitHappyPathBindsExactAssessment() {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst, removeGamma());
        JsonNode assessed = assess(key, analyst);
        ResponseEntity<String> submitted = call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + key + "/submit", analyst, submitBody(assessed));

        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(submitted).get("status").asText()).isEqualTo("AWAITING_APPROVAL");
        JsonNode audit = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit",
                token("TWIN_READ"), null));
        assertThat(audit.size()).isEqualTo(3);
        assertThat(audit.get(2).get("action").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    void submitRejectsMismatchInvalidStateAndExpiry() {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst, removeGamma());
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/submit", analyst,
                        Map.of("revisionNumber", 1, "operationsHash", "x", "assessmentHash", "y"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        JsonNode assessed = assess(key, analyst);
        Map<String, Object> valid = submitBody(assessed);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/submit", analyst,
                        Map.of("revisionNumber", 2, "operationsHash",
                                valid.get("operationsHash"), "assessmentHash",
                                valid.get("assessmentHash"))).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/submit", analyst,
                        Map.of("revisionNumber", 1, "operationsHash", "0".repeat(64),
                                "assessmentHash", valid.get("assessmentHash"))).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/submit", analyst,
                        Map.of("revisionNumber", 1, "operationsHash",
                                valid.get("operationsHash"), "assessmentHash", "0".repeat(64)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        jdbc.update("UPDATE proposal_revision SET expires_at = now() - interval '1 hour'");
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/submit", analyst,
                        valid).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        jdbc.update("UPDATE proposal_revision SET expires_at = now() + interval '1 hour'");
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/submit", analyst,
                        valid).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/submit", analyst,
                        valid).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void amendmentForksNewRevisionPreservingHistory() throws Exception {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst, removeGamma());
        assess(key, analyst);
        List<Map<String, Object>> amended = List.of(
                Map.of("type", "REMOVE_ENTITY", "entity", refB));
        var response = patch("/api/v1/twin/proposals/" + key, analyst,
                Map.of("title", "Amended", "operations", amended));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        JsonNode rev2 = JSON.readTree(response.getContentAsString());
        assertThat(rev2.get("revisionNumber").asInt()).isEqualTo(2);
        assertThat(rev2.get("status").asText()).isEqualTo("DRAFT");
        assertThat(rev2.get("assessment").isNull()).isTrue();
        JsonNode rev1 = body(call(HttpMethod.GET,
                "/api/v1/twin/proposals/" + key + "/revisions/1", token("TWIN_READ"), null));
        assertThat(rev1.get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(rev1.get("assessment").isNull()).isFalse();
        JsonNode audit = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit",
                token("TWIN_READ"), null));
        assertThat(audit.size()).isEqualTo(4);
        assertThat(audit.get(2).get("action").asText()).isEqualTo("SUPERSEDED");
        assertThat(audit.get(3).get("action").asText()).isEqualTo("CREATED");
    }

    @Test
    void amendmentRejectsTerminalUnknownAndStranger() throws Exception {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst, removeGamma());
        call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/withdraw", analyst,
                Map.of("reason", "stop"));
        assertThat(patch("/api/v1/twin/proposals/" + key, analyst,
                        Map.of("title", "Late", "operations", removeGamma())).getStatus())
                .isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(patch("/api/v1/twin/proposals/" + UUID.randomUUID(), analyst,
                        Map.of("title", "Ghost", "operations", removeGamma())).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        String other = draft(token("TWIN_ANALYZE"), removeGamma());
        assertThat(patch("/api/v1/twin/proposals/" + other, token("TWIN_ANALYZE"),
                        Map.of("title", "Hijack", "operations", removeGamma())).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(patch("/api/v1/twin/proposals/" + other, token("TWIN_ADMIN"),
                        Map.of("title", "Admin amend", "operations", removeGamma())).getStatus())
                .isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void concurrentSubmitsYieldOneWinner() throws Exception {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst, removeGamma());
        Map<String, Object> submit = submitBody(assess(key, analyst));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpStatusCode>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate timed out");
                    }
                    return call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/submit",
                            analyst, submit).getStatusCode();
                }));
            }
            start.countDown();
            List<HttpStatusCode> outcomes = new ArrayList<>();
            for (Future<HttpStatusCode> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        JsonNode audit = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit",
                token("TWIN_READ"), null));
        int submitted = 0;
        for (JsonNode event : audit) {
            if ("SUBMITTED".equals(event.get("action").asText())) {
                submitted++;
            }
        }
        assertThat(submitted).isOne();
    }

    @Test
    void concurrentAmendsYieldSingleRevisionTwo() throws Exception {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst, removeGamma());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate timed out");
                    }
                    return patch("/api/v1/twin/proposals/" + key, analyst,
                            Map.of("title", "Race", "operations", removeGamma())).getStatus();
                }));
            }
            start.countDown();
            List<Integer> outcomes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder(
                    HttpStatus.OK.value(), HttpStatus.CONFLICT.value());
        } finally {
            pool.shutdownNow();
        }
        JsonNode history = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/revisions",
                token("TWIN_READ"), null));
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(1).get("revisionNumber").asInt()).isEqualTo(2);
    }

    @Test
    void concurrentSubmitAndAmendCannotContradict() throws Exception {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst, removeGamma());
        Map<String, Object> submit = submitBody(assess(key, analyst));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<HttpStatusCode> submitFuture = pool.submit(() -> {
                if (!start.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate timed out");
                }
                return call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/submit",
                        analyst, submit).getStatusCode();
            });
            Future<Integer> amendFuture = pool.submit(() -> {
                if (!start.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate timed out");
                }
                return patch("/api/v1/twin/proposals/" + key, analyst,
                        Map.of("title", "Race", "operations", removeGamma())).getStatus();
            });
            start.countDown();
            HttpStatusCode submitOutcome = submitFuture.get(60, TimeUnit.SECONDS);
            int amendOutcome = amendFuture.get(60, TimeUnit.SECONDS);
            assertThat(submitOutcome).isIn(HttpStatus.OK, HttpStatus.CONFLICT);
            assertThat(amendOutcome)
                    .isIn(HttpStatus.OK.value(), HttpStatus.CONFLICT.value());
            boolean submitOk = submitOutcome == HttpStatus.OK;
            boolean amendOk = amendOutcome == HttpStatus.OK.value();
            assertThat(submitOk && amendOk).isFalse();
        } finally {
            pool.shutdownNow();
        }
        JsonNode history = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/revisions",
                token("TWIN_READ"), null));
        if (history.size() == 2) {
            assertThat(history.get(1).get("revisionNumber").asInt()).isEqualTo(2);
            assertThat(history.get(0).get("status").asText()).isEqualTo("SUPERSEDED");
        } else {
            assertThat(history.size()).isOne();
            assertThat(history.get(0).get("status").asText())
                    .isIn("AWAITING_APPROVAL", "ASSESSED");
        }
    }

    @Test
    void newEndpointRoles() throws Exception {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst, removeGamma());
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/submit", null,
                        Map.of("revisionNumber", 1)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/submit",
                        token("TWIN_READ"), Map.of("revisionNumber", 1)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patch("/api/v1/twin/proposals/" + key, token("TWIN_READ"),
                        Map.of("title", "x", "operations", removeGamma())).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(patch("/api/v1/twin/proposals/" + key, null,
                        Map.of("title", "x", "operations", removeGamma())).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }
}
