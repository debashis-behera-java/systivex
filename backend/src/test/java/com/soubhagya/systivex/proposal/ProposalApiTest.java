package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.security.TestJwt;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * Phase 11 S3: draft creation, validation, roles, and reads over real
 * HTTP. Twin fixtures use unique external references per test and are
 * removed afterwards, so the shared graph is left untouched; proposal
 * and audit tables are cleaned around each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProposalApiTest extends AbstractPostgresIntegrationTest {

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
        silentErrors();
        prefix = "sec-s3-" + UUID.randomUUID().toString().substring(0, 8);
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
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM proposal_revision");
        jdbc.update("UPDATE audit_chain_head SET last_seq = 0, last_hash = 'GENESIS'");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM proposal_revision");
        jdbc.update("UPDATE audit_chain_head SET last_seq = 0, last_hash = 'GENESIS'");
        jdbc.update("DELETE FROM system_entity WHERE external_ref LIKE '" + prefix + ":%'");
    }

    private String insertEntity(String name, String ref) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO system_entity (id, type, name, external_ref, environment, metadata)"
                        + " VALUES (?, 'SERVICE', ?, ?, 'test', '{}')",
                id, name, ref);
        return id.toString();
    }

    private void silentErrors() {
        http.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response)
                    throws java.io.IOException {
                return false;
            }
        });
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

    private static String subjectOf(String token) {
        String[] parts = token.split("\\.");
        String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        try {
            return JSON.readTree(json).get("sub").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode body(ResponseEntity<String> response) {
        try {
            return JSON.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> draftBody(String title, Object operations) {
        return Map.of("title", title, "operations", operations);
    }

    private List<Map<String, Object>> removeOp(String entity) {
        return List.of(Map.of("type", "REMOVE_ENTITY", "entity", entity));
    }

    @Test
    void createDraftResolvesCanonicalOpsAndJwtIdentity() {
        String proposer = token("TWIN_ANALYZE");
        List<Map<String, Object>> ops = List.of(
                Map.of("type", "REMOVE_ENTITY", "entity", refC),
                Map.of("type", "ADD_RELATIONSHIP", "source", idA, "target", idB,
                        "relationshipType", "READS"));
        ResponseEntity<String> response =
                call(HttpMethod.POST, "/api/v1/twin/proposals", proposer, draftBody("Retire gamma", ops));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        JsonNode created = body(response);
        assertThat(created.get("revisionNumber").asInt()).isOne();
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.get("proposerIssuer").asText()).isEqualTo(TestJwt.ISSUER);
        assertThat(created.get("proposerSubject").asText()).isEqualTo(subjectOf(proposer));
        assertThat(created.get("assessment").isNull()).isTrue();
        assertThat(created.has("jpaVersion")).isFalse();
        String opsJson = created.get("operations").toString();
        assertThat(opsJson).contains(idA).doesNotContain(refC);
        assertThat(created.get("operationsHash").asText()).hasSize(64);
    }

    @Test
    void identicalOperationsYieldIdenticalHashes() {
        String proposer = token("TWIN_ANALYZE");
        List<Map<String, Object>> forward = List.of(
                Map.of("type", "REMOVE_ENTITY", "entity", refC),
                Map.of("type", "ADD_RELATIONSHIP", "source", idA, "target", idB,
                        "relationshipType", "READS"));
        List<Map<String, Object>> reversed = List.of(
                Map.of("type", "ADD_RELATIONSHIP", "source", idA, "target", idB,
                        "relationshipType", "READS"),
                Map.of("type", "REMOVE_ENTITY", "entity", refC));
        String first = body(call(HttpMethod.POST, "/api/v1/twin/proposals", proposer,
                        draftBody("First", forward))).get("operationsHash").asText();
        String second = body(call(HttpMethod.POST, "/api/v1/twin/proposals", proposer,
                        draftBody("Second", reversed))).get("operationsHash").asText();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void creationValidationRejectsBadInput() {
        String proposer = token("TWIN_ANALYZE");
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals", proposer,
                        Map.of("title", "  ", "operations", removeOp(refC))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals", proposer,
                        Map.of("title", "Empty")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals", proposer,
                        draftBody("Ghost", removeOp("no-such-entity"))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals", proposer,
                        draftBody("Shape", List.of(Map.of("type", "REMOVE_ENTITY")))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> duplicate = List.of(
                Map.of("type", "REMOVE_ENTITY", "entity", refC),
                Map.of("type", "REMOVE_ENTITY", "entity", idOf(refC)));
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals", proposer,
                        draftBody("Duplicate", duplicate)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    private String idOf(String ref) {
        return jdbc.queryForObject(
                "SELECT id FROM system_entity WHERE external_ref = ?", String.class, ref);
    }

    @Test
    void creationRoleMatrix() {
        Map<String, Object> draft = draftBody("Matrix", removeOp(refC));
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals", null, draft).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals", token("TWIN_READ"), draft)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals", token("TWIN_ADMIN"), draft)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void readsAreAuthorizedOrderedAndBounded() {
        String analyst = token("TWIN_ANALYZE");
        String reader = token("TWIN_READ");
        for (int i = 1; i <= 3; i++) {
            call(HttpMethod.POST, "/api/v1/twin/proposals", analyst,
                    draftBody("Proposal " + i, removeOp(refC)));
        }
        JsonNode page = body(call(HttpMethod.GET, "/api/v1/twin/proposals", reader, null));
        assertThat(page.get("total").asLong()).isEqualTo(3);
        assertThat(page.get("items").size()).isEqualTo(3);
        JsonNode capped = body(call(
                HttpMethod.GET, "/api/v1/twin/proposals?size=1000", reader, null));
        assertThat(capped.get("size").asInt()).isEqualTo(100);
        String key = page.get("items").get(0).get("proposalKey").asText();
        JsonNode latest = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key, reader, null));
        assertThat(latest.get("revisionNumber").asInt()).isOne();
        JsonNode history =
                body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/revisions", reader, null));
        assertThat(history.size()).isOne();
        JsonNode audit =
                body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit", reader, null));
        assertThat(audit.size()).isOne();
        assertThat(audit.get(0).get("action").asText()).isEqualTo("CREATED");
    }

    @Test
    void unknownKeysAndRevisionsAre404() {
        String reader = token("TWIN_READ");
        String missing = UUID.randomUUID().toString();
        assertThat(call(HttpMethod.GET, "/api/v1/twin/proposals/" + missing, reader, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/proposals/" + missing + "/revisions", reader,
                        null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/proposals/" + missing + "/audit", reader,
                        null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        String proposer = token("TWIN_ANALYZE");
        String key = body(call(HttpMethod.POST, "/api/v1/twin/proposals", proposer,
                        draftBody("Known", removeOp(refC)))).get("proposalKey").asText();
        assertThat(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/revisions/9", reader,
                        null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void readRoleMatrix() {
        String analyst = token("TWIN_ANALYZE");
        String key = body(call(HttpMethod.POST, "/api/v1/twin/proposals", analyst,
                        draftBody("Matrix", removeOp(refC)))).get("proposalKey").asText();
        assertThat(call(HttpMethod.GET, "/api/v1/twin/proposals", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/proposals", analyst, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit",
                        token("TWIN_READ"), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
