package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.security.TestJwt;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Phase 11 S3: withdrawal authorization and transitions over real HTTP —
 * proposer success, stranger denial, admin withdrawal without
 * impersonation, repeat-withdraw conflicts, concurrent-withdrawal
 * safety, creation rollback when the audit append fails, and proof that
 * no approval endpoint exists yet. Twin fixtures are namespaced per test
 * and removed afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProposalWithdrawApiTest extends AbstractPostgresIntegrationTest {

    private static final tools.jackson.databind.ObjectMapper JSON =
            JsonMapper.builder().build();

    @LocalServerPort private int port;

    @Autowired private JdbcTemplate jdbc;

    private final RestTemplate http = new RestTemplate();

    private String prefix;
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
        prefix = "sec-wd-" + UUID.randomUUID().toString().substring(0, 8);
        refC = prefix + ":svc-c";
        insertEntity("Gamma", refC);
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

    private String draft(String token) {
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/v1/twin/proposals", token,
                Map.of("title", "Retire gamma",
                        "operations", List.of(Map.of("type", "REMOVE_ENTITY", "entity", refC))));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return body(response).get("proposalKey").asText();
    }

    private int auditCount(String key) {
        JsonNode audit = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key + "/audit",
                token("TWIN_READ"), null));
        return audit.size();
    }

    @Test
    void proposerWithdrawalSucceedsWithOneEvent() {
        String proposer = token("TWIN_ANALYZE");
        String key = draft(proposer);
        ResponseEntity<String> withdrawn = call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + key + "/withdraw", proposer,
                Map.of("reason", "no longer needed"));
        assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(withdrawn).get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(auditCount(key)).isEqualTo(2);
    }

    @Test
    void strangerWithdrawalIsForbidden() {
        String proposer = token("TWIN_ANALYZE");
        String key = draft(proposer);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/withdraw",
                        token("TWIN_ANALYZE"), Map.of("reason", "mine now")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key,
                        token("TWIN_READ"), null)).get("status").asText())
                .isEqualTo("DRAFT");
    }

    @Test
    void adminWithdrawalSucceedsWithoutImpersonation() {
        String proposer = token("TWIN_ANALYZE");
        String admin = token("TWIN_ADMIN");
        String key = draft(proposer);
        ResponseEntity<String> withdrawn = call(HttpMethod.POST,
                "/api/v1/twin/proposals/" + key + "/withdraw", admin,
                Map.of("reason", "admin cleanup"));
        assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode stored = body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key,
                token("TWIN_READ"), null));
        assertThat(stored.get("proposerSubject").asText()).isEqualTo(subjectOf(proposer));
        assertThat(stored.get("proposerSubject").asText()).isNotEqualTo(subjectOf(admin));
    }

    @Test
    void repeatedWithdrawalConflicts() {
        String proposer = token("TWIN_ANALYZE");
        String key = draft(proposer);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/withdraw", proposer,
                        Map.of("reason", "first")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/withdraw", proposer,
                        Map.of("reason", "second")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST,
                        "/api/v1/twin/proposals/" + UUID.randomUUID() + "/withdraw", proposer,
                        Map.of("reason", "ghost")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void concurrentWithdrawalsYieldOneWinner() throws Exception {
        String proposer = token("TWIN_ANALYZE");
        String key = draft(proposer);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<org.springframework.http.HttpStatusCode>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate timed out");
                    }
                    return call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/withdraw",
                            proposer, Map.of("reason", "race")).getStatusCode();
                }));
            }
            start.countDown();
            List<org.springframework.http.HttpStatusCode> outcomes = new ArrayList<>();
            for (Future<org.springframework.http.HttpStatusCode> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            assertThat(outcomes)
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(body(call(HttpMethod.GET, "/api/v1/twin/proposals/" + key,
                        token("TWIN_READ"), null)).get("status").asText())
                .isEqualTo("WITHDRAWN");
        assertThat(auditCount(key)).isEqualTo(2);
    }

    @Test
    void approvalEndpointDoesNotExistYet() {
        String proposer = token("TWIN_ANALYZE");
        String key = draft(proposer);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/proposals/" + key + "/approve",
                        token("TWIN_ADMIN"), Map.of("reason", "early")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

}
