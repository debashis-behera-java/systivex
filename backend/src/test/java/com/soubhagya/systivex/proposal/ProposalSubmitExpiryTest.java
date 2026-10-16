package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.security.TestJwt;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 11 S6: submit-path lazy expiry over real HTTP. An expired
 * submission persists {@code EXPIRED} plus its audit event and returns
 * 409; amendment stays the TTL-agnostic recovery path; reads never
 * mutate. Fixtures are namespaced per test and removed afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ProposalSubmitExpiryTest extends AbstractPostgresIntegrationTest {

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
        prefix = "sec-s6e-" + UUID.randomUUID().toString().substring(0, 8);
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

    private List<Map<String, Object>> removeGamma() {
        return List.of(Map.of("type", "REMOVE_ENTITY", "entity", refC));
    }

    private String draft(String analyst) {
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/v1/twin/proposals", analyst,
                Map.of("title", "Change", "operations", removeGamma()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return body(response).get("proposalKey").asText();
    }

    private JsonNode assess(String key, String analyst) {
        JsonNode assessed = body(call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + key + "/assess", analyst, null));
        assertThat(assessed.get("status").asText()).isEqualTo("ASSESSED");
        return assessed;
    }

    private ResponseEntity<String> submit(String key, String analyst, JsonNode assessed) {
        return call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/submit", analyst,
                Map.of("revisionNumber", assessed.get("revisionNumber").asInt(), "operationsHash",
                        assessed.get("operationsHash").asText(), "assessmentHash",
                        assessed.get("assessmentHash").asText()));
    }

    private JsonNode latest(String key) {
        return body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key, token("TWIN_READ"),
                null));
    }

    private JsonNode audit(String key) {
        return body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit",
                token("TWIN_READ"), null));
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

    @Test
    void expiredSubmitPersistsExpiredAndReturns409() {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst);
        JsonNode assessed = assess(key, analyst);
        jdbc.update("UPDATE proposal_revision SET expires_at = now() - interval '1 hour'"
                + " WHERE proposal_key = ?::uuid", key);

        assertThat(submit(key, analyst, assessed).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        JsonNode stored = latest(key);
        assertThat(stored.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(stored.get("revisionNumber").asInt()).isEqualTo(1);
        JsonNode trail = audit(key);
        assertThat(trail.get(trail.size() - 1).get("action").asText()).isEqualTo("EXPIRED");
        assertThat(trail.get(trail.size() - 1).get("operationsHash").asText())
                .isEqualTo(assessed.get("operationsHash").asText());
        assertThat(trail.get(trail.size() - 1).get("assessmentHash").asText())
                .isEqualTo(assessed.get("assessmentHash").asText());
        assertThat(body(call(HttpMethod.GET, "/api/v1/twin/audit/verify", token("TWIN_READ"),
                null)).get("status").asText()).isEqualTo("OK");
    }

    @Test
    void hardMaximumBindsSubmitEvenWithLaterExpiry() {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst);
        JsonNode assessed = assess(key, analyst);
        jdbc.update("UPDATE proposal_revision SET created_at = now() - interval '31 days',"
                + " expires_at = now() + interval '1 day' WHERE proposal_key = ?::uuid", key);

        assertThat(submit(key, analyst, assessed).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(latest(key).get("status").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void validSubmitStillSucceeds() {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst);
        JsonNode assessed = assess(key, analyst);

        assertThat(submit(key, analyst, assessed).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(latest(key).get("status").asText()).isEqualTo("AWAITING_APPROVAL");
        JsonNode trail = audit(key);
        assertThat(trail.get(trail.size() - 1).get("action").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    void amendmentRecoversExpiredAssessmentAsNewDraft() throws Exception {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst);
        JsonNode assessed = assess(key, analyst);
        jdbc.update("UPDATE proposal_revision SET expires_at = now() - interval '1 hour'"
                + " WHERE proposal_key = ?::uuid", key);

        var amended = patch("/api/v1/twin/proposals/" + key, analyst,
                Map.of("title", "Recovered", "operations", removeGamma()));
        assertThat(amended.getStatus()).isEqualTo(HttpStatus.OK.value());
        JsonNode rev2 = JSON.readTree(amended.getContentAsString());
        assertThat(rev2.get("revisionNumber").asInt()).isEqualTo(2);
        assertThat(rev2.get("status").asText()).isEqualTo("DRAFT");
        assertThat(rev2.get("assessment").isNull()).isTrue();
        JsonNode rev1 = body(call(HttpMethod.GET,
                "/api/v1/twin/proposals/" + key + "/revisions/1", token("TWIN_READ"), null));
        assertThat(rev1.get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(rev1.get("assessment").isNull()).isFalse();

        JsonNode reassessed = body(call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + key + "/assess", analyst, null));
        assertThat(reassessed.get("revisionNumber").asInt()).isEqualTo(2);
        Instant expires = Instant.parse(reassessed.get("expiresAt").asText());
        assertThat(expires).isAfter(Instant.now().plusSeconds(6 * 24 * 3600));
        assertThat(submit(key, analyst, reassessed).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(latest(key).get("status").asText()).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void readsDoNotMutateProposalStateOrAudit() {
        String analyst = token("TWIN_ANALYZE");
        String key = draft(analyst);
        submit(key, analyst, assess(key, analyst));
        int revisionsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM proposal_revision WHERE proposal_key = ?::uuid",
                Integer.class, key);
        int eventsBefore =
                jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class);
        String statusBefore = latest(key).get("status").asText();

        assertThat(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key, token("TWIN_READ"),
                null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/revisions",
                token("TWIN_READ"), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/revisions/1",
                token("TWIN_READ"), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit",
                token("TWIN_READ"), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/audit/verify", token("TWIN_READ"), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM proposal_revision WHERE proposal_key = ?::uuid",
                        Integer.class, key)).isEqualTo(revisionsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class))
                .isEqualTo(eventsBefore);
        assertThat(latest(key).get("status").asText()).isEqualTo(statusBefore);
    }
}
