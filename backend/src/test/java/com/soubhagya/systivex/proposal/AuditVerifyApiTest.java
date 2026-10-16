package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.security.TestJwt;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import java.util.List;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * Phase 11 S2: proves the audit-verify endpoint over real HTTP —
 * {@code TWIN_READ} reads the bounded verification result, missing
 * credentials are 401, insufficient roles are 403, and no HTTP endpoint
 * permits arbitrary audit appends. Cleans the audit tables around each
 * test so the shared database is left untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditVerifyApiTest extends AbstractPostgresIntegrationTest {

    @LocalServerPort private int port;

    @Autowired private JdbcTemplate jdbc;

    private final RestTemplate http = new RestTemplate();

    @BeforeEach
    @AfterEach
    void resetChain() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM proposal_revision");
        jdbc.update("UPDATE audit_chain_head SET last_seq = 0, last_hash = 'GENESIS'");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(url(path), method, new HttpEntity<>(headers), String.class);
    }

    private static String token(String... roles) {
        return TestJwt.token(List.of(roles));
    }

    @BeforeEach
    void silentErrors() {
        http.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response)
                    throws java.io.IOException {
                return false;
            }
        });
    }

    @Test
    void readRoleReadsVerification() {
        ResponseEntity<String> response =
                call(HttpMethod.GET, "/api/v1/twin/audit/verify", token("TWIN_READ"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"OK\"");
    }

    @Test
    void missingCredentialsAreRejected() {
        assertThat(call(HttpMethod.GET, "/api/v1/twin/audit/verify", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void insufficientRolesAreDenied() {
        assertThat(call(HttpMethod.GET, "/api/v1/twin/audit/verify", token("TWIN_ANALYZE"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/twin/audit/verify", token("SUPERUSER"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void tamperedChainSurfacesAsBrokenOverHttp() {
        jdbc.update("UPDATE audit_chain_head SET last_hash = 'tampered'");
        ResponseEntity<String> response =
                call(HttpMethod.GET, "/api/v1/twin/audit/verify", token("TWIN_READ"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"BROKEN\"");
    }

    @Test
    void noEndpointPermitsArbitraryAppends() {
        assertThat(call(HttpMethod.POST, "/api/v1/twin/audit/verify", token("TWIN_ADMIN"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/twin/audit/events", token("TWIN_ADMIN"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class))
                .isZero();
    }
}
