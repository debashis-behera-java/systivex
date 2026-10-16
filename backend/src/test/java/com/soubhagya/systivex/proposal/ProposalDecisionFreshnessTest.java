package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.security.TestJwt;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import java.time.Instant;
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
 * Phase 11 S5: approval freshness, expiry, and concurrency over real
 * HTTP. Structural and runtime staleness are checked separately: a
 * runtime-only change keeps the fingerprint but fails the risk recheck.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ProposalDecisionFreshnessTest extends AbstractPostgresIntegrationTest {

    private static final tools.jackson.databind.ObjectMapper JSON =
            JsonMapper.builder().build();

    @LocalServerPort private int port;

    @Autowired private MockMvc mockMvc;

    @Autowired private JdbcTemplate jdbc;

    private final RestTemplate http = new RestTemplate();

    private String prefix;
    private String refA;
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
        prefix = "sec-s5f-" + UUID.randomUUID().toString().substring(0, 8);
        refA = prefix + ":svc-a";
        refB = prefix + ":svc-b";
        refC = prefix + ":svc-c";
        insertEntity("Alpha", refA);
        insertEntity("Beta", refB);
        insertEntity("Gamma", refC);
        jdbc.update(
                "INSERT INTO system_relationship (id, source_entity_id, target_entity_id,"
                        + " relationship_type) VALUES (?, ?, ?, 'CALLS')",
                UUID.randomUUID(), UUID.fromString(idOf(refA)), UUID.fromString(idOf(refB)));
        jdbc.update(
                "INSERT INTO system_relationship (id, source_entity_id, target_entity_id,"
                        + " relationship_type) VALUES (?, ?, ?, 'CALLS')",
                UUID.randomUUID(), UUID.fromString(idOf(refB)), UUID.fromString(idOf(refC)));
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

    private String idOf(String ref) {
        return jdbc.queryForObject(
                "SELECT id FROM system_entity WHERE external_ref = ?", String.class, ref);
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

    private List<Map<String, Object>> linkAB() {
        return List.of(Map.of("type", "ADD_RELATIONSHIP", "source", refA, "target", refB,
                "relationshipType", "DEPENDS_ON"));
    }

    private String submittedKey(String analyst, List<Map<String, Object>> ops) {
        ResponseEntity<String> drafted = call(HttpMethod.POST, "/api/v1/twin/proposals", analyst,
                Map.of("title", "Change", "operations", ops));
        assertThat(drafted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String key = body(drafted).get("proposalKey").asText();
        JsonNode assessed = body(call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + key + "/assess", analyst, null));
        ResponseEntity<String> submitted = call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + key + "/submit", analyst,
                Map.of("revisionNumber", assessed.get("revisionNumber").asInt(), "operationsHash",
                        assessed.get("operationsHash").asText(), "assessmentHash",
                        assessed.get("assessmentHash").asText()));
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        return key;
    }

    private void setRuntime(String ref, String status, Instant observedAt) {
        String runtime = "{\"runtime\": {\"observedAt\": \"" + observedAt
                + "\", \"health\": {\"status\": \"" + status + "\", \"available\": true}}}";
        jdbc.update("UPDATE system_entity SET metadata = ?::jsonb WHERE external_ref = ?",
                runtime, ref);
    }

    private String statusOf(String key) {
        return body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key, token("TWIN_READ"),
                null)).get("status").asText();
    }

    @Test
    void structuralChangeAfterSubmitBlocksApproval() {
        String key = submittedKey(token("TWIN_ANALYZE"), linkAB());
        insertEntity("Delta", prefix + ":svc-d");
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve",
                        token("TWIN_APPROVER"), Map.of("reason", "stale")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(key)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void newlyStaleRuntimeBlocksApprovalWithoutFingerprintChange() {
        setRuntime(refC, "UP", Instant.now());
        String key = submittedKey(token("TWIN_ANALYZE"), linkAB());
        String before = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key,
                        token("TWIN_READ"), null)).get("twinFingerprint").asText();
        setRuntime(refC, "UP", Instant.now().minusSeconds(2 * 3600));
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve",
                        token("TWIN_APPROVER"), Map.of("reason", "stale")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(key)).isEqualTo("AWAITING_APPROVAL");
        assertThat(body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key,
                        token("TWIN_READ"), null)).get("twinFingerprint").asText())
                .isEqualTo(before);
    }

    @Test
    void riskEscalationBlocksApproval() {
        setRuntime(refC, "UP", Instant.now());
        String key = submittedKey(token("TWIN_ANALYZE"), linkAB());
        setRuntime(refC, "DOWN", Instant.now());
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve",
                        token("TWIN_APPROVER"), Map.of("reason", "escalated")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(key)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void expiredAssessmentMarksExpiredAndBlocksDecision() {
        String key = submittedKey(token("TWIN_ANALYZE"), linkAB());
        jdbc.update("UPDATE proposal_revision SET expires_at = now() - interval '1 hour'"
                + " WHERE proposal_key = ?::uuid", key);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve",
                        token("TWIN_APPROVER"), Map.of("reason", "late")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(key)).isEqualTo("EXPIRED");
        JsonNode audit = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit",
                token("TWIN_READ"), null));
        assertThat(audit.get(audit.size() - 1).get("action").asText()).isEqualTo("EXPIRED");
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/reject",
                        token("TWIN_APPROVER"), Map.of("reason", "late")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void thirtyDayHardMaximumExpiresLongLivedRevisions() {
        String key = submittedKey(token("TWIN_ANALYZE"), linkAB());
        jdbc.update("UPDATE proposal_revision SET created_at = now() - interval '31 days',"
                + " expires_at = now() + interval '1 day' WHERE proposal_key = ?::uuid", key);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve",
                        token("TWIN_APPROVER"), Map.of("reason", "too old")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(key)).isEqualTo("EXPIRED");
    }

    @Test
    void concurrentApprovalsYieldOneWinner() throws Exception {
        String key = submittedKey(token("TWIN_ANALYZE"), linkAB());
        String approver = token("TWIN_APPROVER");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpStatusCode>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate timed out");
                    }
                    return call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve",
                            approver, Map.of("reason", "race")).getStatusCode();
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
        assertThat(statusOf(key)).isEqualTo("APPROVED");
        JsonNode audit = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit",
                token("TWIN_READ"), null));
        int approved = 0;
        for (JsonNode event : audit) {
            if ("APPROVED".equals(event.get("action").asText())) {
                approved++;
            }
        }
        assertThat(approved).isOne();
    }

    @Test
    void rejectSucceedsDespiteStructuralDrift() {
        String key = submittedKey(token("TWIN_ANALYZE"), linkAB());
        insertEntity("Delta", prefix + ":svc-d");
        ResponseEntity<String> decided = call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + key + "/reject", token("TWIN_APPROVER"),
                Map.of("reason", "drift is fine for refusal"));
        assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(decided).get("status").asText()).isEqualTo("REJECTED");
    }

    @Test
    void rejectOnExpiredMarksExpired() {
        String key = submittedKey(token("TWIN_ANALYZE"), linkAB());
        jdbc.update("UPDATE proposal_revision SET expires_at = now() - interval '1 hour'"
                + " WHERE proposal_key = ?::uuid", key);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/reject",
                        token("TWIN_APPROVER"), Map.of("reason", "too late")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(key)).isEqualTo("EXPIRED");
    }

    @Test
    void concurrentDecisionsOnDifferentProposalsKeepChainValid() throws Exception {
        String analyst = token("TWIN_ANALYZE");
        String first = submittedKey(analyst, linkAB());
        String second = submittedKey(analyst, linkAB());
        String approver = token("TWIN_APPROVER");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<HttpStatusCode> outcomes = new ArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<String> keys = List.of(first, second);
            List<Future<HttpStatusCode>> futures = new ArrayList<>();
            for (String target : keys) {
                futures.add(pool.submit(() -> {
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate timed out");
                    }
                    return call(HttpMethod.POST, "/api/v1/twin/proposals/" + target + "/approve",
                            approver, Map.of("reason", "race")).getStatusCode();
                }));
            }
            start.countDown();
            for (Future<HttpStatusCode> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(outcomes).doesNotContain(HttpStatus.INTERNAL_SERVER_ERROR);
        for (String key : List.of(first, second)) {
            String status = statusOf(key);
            assertThat(status).isIn("APPROVED", "AWAITING_APPROVAL");
            JsonNode audit = body(call(HttpMethod.GET,
                    "/api/v1/twin/proposals/" + key + "/audit", token("TWIN_READ"), null));
            int approved = 0;
            for (JsonNode event : audit) {
                if ("APPROVED".equals(event.get("action").asText())) {
                    approved++;
                }
            }
            assertThat(approved).isEqualTo("APPROVED".equals(status) ? 1 : 0);
        }
        assertThat(body(call(HttpMethod.GET, "/api/v1/twin/audit/verify", token("TWIN_READ"),
                null)).get("status").asText()).isEqualTo("OK");
    }

    @Test
    void decidedRevisionsCannotBeAmended() throws Exception {
        String analyst = token("TWIN_ANALYZE");
        String key = submittedKey(analyst, linkAB());
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve",
                        token("TWIN_APPROVER"), Map.of("reason", "done")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(patch("/api/v1/twin/proposals/" + key, analyst,
                        Map.of("title", "Late", "operations", linkAB())).getStatus())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

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
}
