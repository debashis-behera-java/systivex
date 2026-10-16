package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.SystivexApplication;
import com.soubhagya.systivex.proposal.service.AuditService;
import com.soubhagya.systivex.security.TestJwt;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * Phase 11 S3: proves proposal creation rolls back when the audit append
 * fails. A test-only {@code @Primary} audit service throws on every
 * append; the draft must then fail with a 5xx and leave neither a
 * proposal row nor an audit event behind.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {SystivexApplication.class, ProposalCreationRollbackTest.FailingAuditConfig.class})
class ProposalCreationRollbackTest extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class FailingAuditConfig {
        @Bean
        @Primary
        AuditService failingAudits() {
            AuditService failing = Mockito.mock(AuditService.class);
            Mockito.doThrow(new IllegalStateException("injected audit failure"))
                    .when(failing)
                    .append(Mockito.any());
            return failing;
        }
    }

    @LocalServerPort private int port;

    @Autowired private JdbcTemplate jdbc;

    private final RestTemplate http = new RestTemplate();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM proposal_revision");
        jdbc.update("UPDATE audit_chain_head SET last_seq = 0, last_hash = 'GENESIS'");
        jdbc.update("DELETE FROM system_entity WHERE external_ref LIKE 'sec-rb-%'");
    }

    @Test
    void auditFailureRollsBackCreation() {
        http.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response)
                    throws java.io.IOException {
                return false;
            }
        });
        String ref = "sec-rb-" + UUID.randomUUID() + ":svc";
        jdbc.update(
                "INSERT INTO system_entity (id, type, name, external_ref, environment, metadata)"
                        + " VALUES (?, 'SERVICE', 'Target', ?, 'test', '{}')",
                UUID.randomUUID(), ref);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(TestJwt.token(List.of("TWIN_ANALYZE")));
            ResponseEntity<String> response = http.exchange(
                    "http://localhost:" + port + "/api/v1/twin/proposals", HttpMethod.POST,
                    new HttpEntity<>(
                            Map.of("title", "Rollback", "operations",
                                    List.of(Map.of("type", "REMOVE_ENTITY", "entity", ref))),
                            headers),
                    String.class);
            assertThat(response.getStatusCode().is5xxServerError()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM proposal_revision", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class))
                    .isZero();
        } finally {
            jdbc.update("DELETE FROM audit_event");
            jdbc.update("DELETE FROM proposal_revision");
            jdbc.update("DELETE FROM system_entity WHERE external_ref = ?", ref);
        }
    }
}
