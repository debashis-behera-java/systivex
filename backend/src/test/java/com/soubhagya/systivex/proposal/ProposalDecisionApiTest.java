package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.soubhagya.systivex.proposal.api.ProposalResponse;
import com.soubhagya.systivex.proposal.model.ProposalRevision;
import com.soubhagya.systivex.proposal.model.ProposalStatus;
import com.soubhagya.systivex.proposal.repository.ProposalRevisionRepository;
import com.soubhagya.systivex.proposal.service.ProposalDecisionService;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 11 S5: approval and rejection over real HTTP. Twin fixtures are
 * namespaced per test and removed afterwards; proposal and audit tables
 * plus the chain head are reset around each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProposalDecisionApiTest extends AbstractPostgresIntegrationTest {

    private static final tools.jackson.databind.ObjectMapper JSON =
            JsonMapper.builder().build();

    @LocalServerPort private int port;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private ProposalDecisionService decisions;

    @Autowired private ProposalRevisionRepository revisions;

    @Autowired private PlatformTransactionManager transactions;

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
        prefix = "sec-s5d-" + UUID.randomUUID().toString().substring(0, 8);
        refA = prefix + ":svc-a";
        refB = prefix + ":svc-b";
        refC = prefix + ":svc-c";
        insertEntity("Alpha", refA);
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

    private String draft(String token, List<Map<String, Object>> ops) {
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/v1/twin/proposals", token,
                Map.of("title", "Change", "operations", ops));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return body(response).get("proposalKey").asText();
    }

    private JsonNode assess(String key, String token) {
        ResponseEntity<String> response =
                call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/assess", token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return body(response);
    }

    private void submit(String key, String token, JsonNode assessed) {
        ResponseEntity<String> response = call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + key + "/submit", token,
                Map.of("revisionNumber", assessed.get("revisionNumber").asInt(), "operationsHash",
                        assessed.get("operationsHash").asText(), "assessmentHash",
                        assessed.get("assessmentHash").asText()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String submittedKey(String analyst) {
        String key = draft(analyst, removeGamma());
        submit(key, analyst, assess(key, analyst));
        return key;
    }

    private List<Map<String, Object>> removeGamma() {
        return List.of(Map.of("type", "REMOVE_ENTITY", "entity", refC));
    }

    private void setRuntime(String ref, String status, Instant observedAt) {
        String runtime = "{\"runtime\": {\"observedAt\": \"" + observedAt
                + "\", \"health\": {\"status\": \"" + status + "\", \"available\": true}}}";
        jdbc.update("UPDATE system_entity SET metadata = ?::jsonb WHERE external_ref = ?",
                runtime, ref);
    }

    @Test
    void approveHappyPathRecordsApproverAndReason() {
        String analyst = token("TWIN_ANALYZE");
        String approver = TestJwt.tokenWithSubject("approver-1", List.of("TWIN_APPROVER"));
        String key = submittedKey(analyst);
        ResponseEntity<String> decided = call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + key + "/approve", approver,
                Map.of("reason", "Verified against the twin"));

        assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode revision = body(decided);
        assertThat(revision.get("status").asText()).isEqualTo("APPROVED");
        assertThat(revision.get("approverSubject").asText()).isEqualTo("approver-1");
        assertThat(revision.get("approverIssuer").asText()).isEqualTo(TestJwt.ISSUER);
        assertThat(revision.get("reason").asText()).isEqualTo("Verified against the twin");
        JsonNode audit = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit",
                token("TWIN_READ"), null));
        assertThat(audit.size()).isEqualTo(4);
        assertThat(audit.get(3).get("action").asText()).isEqualTo("APPROVED");
        assertThat(audit.get(3).get("reason").asText()).isEqualTo("Verified against the twin");
        assertThat(audit.get(3).get("actorSubject").asText()).isEqualTo("approver-1");
        assertThat(audit.get(3).get("actorIssuer").asText()).isEqualTo(TestJwt.ISSUER);
        assertThat(audit.get(3).get("proposalKey").asText()).isEqualTo(key);
        assertThat(audit.get(3).get("revisionNumber").asInt()).isEqualTo(1);
        assertThat(audit.get(3).get("operationsHash").asText())
                .isEqualTo(revision.get("operationsHash").asText());
        assertThat(audit.get(3).get("assessmentHash").asText())
                .isEqualTo(revision.get("assessmentHash").asText());
    }

    @Test
    void rejectHappyPath() {
        String analyst = token("TWIN_ANALYZE");
        String approver = token("TWIN_APPROVER");
        String key = submittedKey(analyst);
        ResponseEntity<String> decided = call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + key + "/reject", approver,
                Map.of("reason", "Too risky this week"));

        assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(decided).get("status").asText()).isEqualTo("REJECTED");
        assertThat(body(decided).get("reason").asText()).isEqualTo("Too risky this week");
        JsonNode audit = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit",
                token("TWIN_READ"), null));
        assertThat(audit.size()).isEqualTo(4);
        assertThat(audit.get(3).get("action").asText()).isEqualTo("REJECTED");
    }

    @Test
    void missingBlankAndLongReasonsAre400() {
        String approver = token("TWIN_APPROVER");
        String key = submittedKey(token("TWIN_ANALYZE"));
        String approve = "/api/v1/twin/proposals/" + key + "/approve";
        assertThat(call(HttpMethod.POST, approve, approver, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, approve, approver, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, approve, approver, Map.of("reason", "  "))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, approve, approver, Map.of("reason", "x".repeat(1025)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key,
                        token("TWIN_READ"), null)).get("status").asText())
                .isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void decisionRoleMatrix() {
        String key = submittedKey(token("TWIN_ANALYZE"));
        String approve = "/api/v1/twin/proposals/" + key + "/approve";
        Map<String, String> reason = Map.of("reason", "ok");
        assertThat(call(HttpMethod.POST, approve, null, reason).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST, approve, token("TWIN_READ"), reason).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, approve, token("TWIN_ANALYZE"), reason).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, approve, token("TWIN_ADMIN"), reason).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, approve, token("TWIN_APPROVER"), reason).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void proposerCannotDecideOwnProposal() {
        String proposer = TestJwt.tokenWithSubject("proposer-1", List.of("TWIN_ANALYZE"));
        String selfApprover = TestJwt.tokenWithSubject("proposer-1", List.of("TWIN_APPROVER"));
        String key = submittedKey(proposer);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve",
                        selfApprover, Map.of("reason", "self")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/reject",
                        selfApprover, Map.of("reason", "self")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key,
                        token("TWIN_READ"), null)).get("status").asText())
                .isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void dualRoleProposerStillBlocked() {
        String both = TestJwt.tokenWithSubject("both-1", List.of("TWIN_ANALYZE", "TWIN_APPROVER"));
        String key = submittedKey(both);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve", both,
                        Map.of("reason", "self")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void separationComparesIssuerSubjectPair() {
        String proposer = TestJwt.tokenWithSubject("pair-subject", List.of("TWIN_ANALYZE"));
        String key = submittedKey(proposer);
        UUID proposalKey = UUID.fromString(key);
        ProposalResponse outcome = new TransactionTemplate(transactions).execute(status ->
                decisions.approve(proposalKey, "cross-issuer review", "https://other-issuer",
                        "pair-subject"));
        assertThat(outcome.status()).isEqualTo("APPROVED");
        ProposalRevision stored =
                revisions.findByProposalKeyAndRevisionNumber(proposalKey, 1).orElseThrow();
        assertThat(stored.getApproverIssuer()).isEqualTo("https://other-issuer");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
                decisions.reject(proposalKey, "late", TestJwt.ISSUER, "pair-subject")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void wrongStatusDecisionsAre409() {
        String analyst = token("TWIN_ANALYZE");
        String approver = token("TWIN_APPROVER");
        String draftKey = draft(analyst, removeGamma());
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + draftKey + "/approve",
                        approver, Map.of("reason", "early")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        String key = submittedKey(analyst);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve", approver,
                        Map.of("reason", "first")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve", approver,
                        Map.of("reason", "again")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/reject", approver,
                        Map.of("reason", "after")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void denyCannotBeApprovedButCanBeRejected() {
        jdbc.update(
                "INSERT INTO system_relationship (id, source_entity_id, target_entity_id,"
                        + " relationship_type) VALUES (?, ?, ?, 'CALLS')",
                UUID.randomUUID(), UUID.fromString(idOf(refA)), UUID.fromString(idOf(refC)));
        setRuntime(refC, "DOWN", Instant.now());
        String analyst = token("TWIN_ANALYZE");
        String approver = token("TWIN_APPROVER");
        String key = draft(analyst, List.of(Map.of("type", "ADD_RELATIONSHIP", "source", refA,
                "target", refB, "relationshipType", "DEPENDS_ON")));
        submit(key, analyst, assess(key, analyst));
        assertThat(body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key,
                        token("TWIN_READ"), null)).get("policyDecision").asText())
                .isEqualTo("DENY");
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve", approver,
                        Map.of("reason", "override attempt")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/reject", approver,
                        Map.of("reason", "correctly denied")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private String idOf(String ref) {
        return jdbc.queryForObject(
                "SELECT id FROM system_entity WHERE external_ref = ?", String.class, ref);
    }
}
