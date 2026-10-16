package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.security.TestJwt;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 11 S4: server-side assessment over real HTTP. Twin fixtures are
 * namespaced per test and removed afterwards; proposal and audit tables
 * plus the chain head are reset around each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProposalAssessApiTest extends AbstractPostgresIntegrationTest {

    private static final tools.jackson.databind.ObjectMapper JSON =
            JsonMapper.builder().build();

    @LocalServerPort private int port;

    @Autowired private JdbcTemplate jdbc;

    private final RestTemplate http = new RestTemplate();

    private String prefix;
    private String refA;
    private String refB;
    private String refC;
    private String idA;
    private String idB;

    @BeforeEach
    void setup() {
        http.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response)
                    throws java.io.IOException {
                return false;
            }
        });
        prefix = "sec-s4a-" + UUID.randomUUID().toString().substring(0, 8);
        refA = prefix + ":svc-a";
        refB = prefix + ":svc-b";
        refC = prefix + ":svc-c";
        idA = insertEntity("Alpha", refA);
        idB = insertEntity("Beta", refB);
        insertEntity("Gamma", refC);
        jdbc.update(
                "INSERT INTO system_relationship (id, source_entity_id, target_entity_id,"
                        + " relationship_type) VALUES (?, ?, ?, 'CALLS')",
                UUID.randomUUID(), UUID.fromString(idA), UUID.fromString(idB));
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

    private String insertEntity(String name, String ref) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO system_entity (id, type, name, external_ref, environment, metadata)"
                        + " VALUES (?, 'SERVICE', ?, ?, 'test', '{}')",
                id, name, ref);
        return id.toString();
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

    private String draft(String token, List<Map<String, Object>> ops) {
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/v1/twin/proposals", token,
                Map.of("title", "Change", "operations", ops));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return body(response).get("proposalKey").asText();
    }

    private List<Map<String, Object>> removeGamma() {
        return List.of(Map.of("type", "REMOVE_ENTITY", "entity", refC));
    }

    @Test
    void assessComputesServerSideEvidence() {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst, removeGamma());
        ResponseEntity<String> assessed =
                call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/assess", analyst, null);

        assertThat(assessed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode revision = body(assessed);
        assertThat(revision.get("status").asText()).isEqualTo("ASSESSED");
        assertThat(revision.get("policyDecision").asText()).isIn("ALLOW", "REVIEW", "DENY");
        assertThat(revision.get("policyVersion").asText()).isEqualTo("policy-v1");
        assertThat(revision.get("riskTaxonomy").asText()).isEqualTo("risk-v1");
        assertThat(revision.get("snapshotObservedAt").asText()).isNotBlank();
        assertThat(revision.get("twinFingerprint").asText()).hasSize(64);
        assertThat(revision.get("assessmentHash").asText()).hasSize(64);
        assertThat(revision.get("assessment").get("simulation").get("limitations").size())
                .isGreaterThan(0);
        assertThat(revision.get("assessment").get("policy").get("rulesFired").isArray()).isTrue();
        assertThat(revision.get("assessment").get("risk").get("levels").isObject()).isTrue();
        JsonNode audit = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit",
                token("TWIN_READ"), null));
        assertThat(audit.size()).isEqualTo(2);
        assertThat(audit.get(1).get("action").asText()).isEqualTo("ASSESSED");
    }

    @Test
    void fingerprintIsDeterministicForUnchangedContent() {
        String analyst = token("TWIN_ANALYZE");
        String first = draft(analyst, removeGamma());
        String second = draft(analyst, removeGamma());
        String fp1 = body(call(HttpMethod.POST, "/api/v1/twin/proposals/" + first + "/assess",
                        analyst, null)).get("twinFingerprint").asText();
        JsonNode assessed2 = body(call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + second + "/assess", analyst, null));
        assertThat(assessed2.get("twinFingerprint").asText()).isEqualTo(fp1);
    }

    @Test
    void runtimeOnlyRefreshPreservesFingerprint() {
        String analyst = token("TWIN_ANALYZE");
        String first = draft(analyst, removeGamma());
        String before = body(call(HttpMethod.POST, "/api/v1/twin/proposals/" + first + "/assess",
                        analyst, null)).get("twinFingerprint").asText();
        jdbc.update(
                "UPDATE system_entity SET metadata = metadata ||"
                        + " '{\"runtime\": {\"observedAt\": \"2030-01-01T00:00:00Z\","
                        + " \"health\": {\"status\": \"UP\"}}}' WHERE external_ref = ?",
                refA);
        String second = draft(analyst, removeGamma());
        String after = body(call(HttpMethod.POST, "/api/v1/twin/proposals/" + second + "/assess",
                        analyst, null)).get("twinFingerprint").asText();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void structuralChangeAltersFingerprint() {
        String analyst = token("TWIN_ANALYZE");
        String first = draft(analyst, removeGamma());
        String before = body(call(HttpMethod.POST, "/api/v1/twin/proposals/" + first + "/assess",
                        analyst, null)).get("twinFingerprint").asText();
        insertEntity("Delta", prefix + ":svc-d");
        String second = draft(analyst, removeGamma());
        String after = body(call(HttpMethod.POST, "/api/v1/twin/proposals/" + second + "/assess",
                        analyst, null)).get("twinFingerprint").asText();
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void deletedEntityBlocksAssessment() {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst, removeGamma());
        jdbc.update("DELETE FROM system_entity WHERE external_ref = ?", refC);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/assess", analyst,
                        null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key,
                        token("TWIN_READ"), null)).get("status").asText())
                .isEqualTo("DRAFT");
    }

    @Test
    void conflictSinceDraftBlocksAssessment() {
        String analyst = token("TWIN_ANALYZE");
        String admin = token("TWIN_ADMIN");
        List<Map<String, Object>> ops = List.of(Map.of("type", "ADD_RELATIONSHIP", "source", idA,
                "target", refC, "relationshipType", "CALLS"));
        String key = draft(analyst, ops);
        ResponseEntity<String> edge = call(HttpMethod.POST, "/api/v1/twin/relationships", admin,
                Map.of("sourceId", idA, "targetId", idOf(refC), "type", "CALLS"));
        assertThat(edge.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/assess", analyst,
                        null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key,
                        token("TWIN_READ"), null)).get("status").asText())
                .isEqualTo("DRAFT");
    }

    private String idOf(String ref) {
        return jdbc.queryForObject(
                "SELECT id FROM system_entity WHERE external_ref = ?", String.class, ref);
    }

    @Test
    void assessRoleMatrix() {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst, removeGamma());
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/assess", null, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/assess",
                        token("TWIN_READ"), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/assess", analyst,
                        null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/assess", analyst,
                        null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void storedEvidenceMatchesTwinAndTaxonomies() {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst, removeGamma());
        JsonNode assessed = body(call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + key + "/assess", analyst, null));
        int entities = jdbc.queryForObject("SELECT count(*) FROM system_entity", Integer.class);
        assertThat(assessed.get("assessment").get("simulation").get("graph")
                        .get("baselineEntityCount").asInt())
                .isEqualTo(entities);
        assertThat(assessed.get("assessment").get("risk").get("taxonomyVersion").asText())
                .isEqualTo("risk-v1");
        assertThat(assessed.get("assessment").get("settings").get("rootCount").asInt())
                .isGreaterThanOrEqualTo(1);
    }
}
