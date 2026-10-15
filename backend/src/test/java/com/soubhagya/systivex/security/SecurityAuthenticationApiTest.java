package com.soubhagya.systivex.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * Token validation over real HTTP: missing, malformed, expired, not-yet-valid,
 * wrong issuer/audience/signature bearer credentials are 401; malformed or
 * unknown roles and insufficient authority are 403; error bodies stay safe.
 * Uses a plain RestTemplate so every Authorization header here is explicit.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.endpoints.web.exposure.include=health,prometheus",
            "management.endpoint.health.show-details=never"
        })
class SecurityAuthenticationApiTest extends AbstractPostgresIntegrationTest {

    @LocalServerPort private int port;

    private final RestTemplate http = new RestTemplate();

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

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> getRawAuth(String path, String headerValue, boolean withHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (withHeader) {
            headers.set("Authorization", headerValue);
        }
        return http.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    @Test
    void validReadTokenReachesTwinGet() {
        ResponseEntity<String> response =
                get("/api/v1/twin/entities", TestJwt.token(List.of("TWIN_READ")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void missingTokenIs401() {
        ResponseEntity<String> response = get("/api/v1/twin/entities", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Authentication required");
    }

    @Test
    void malformedTokensAre401() {
        assertThat(getRawAuth("/api/v1/twin/entities", "Bearer not-a-jwt", true)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getRawAuth("/api/v1/twin/entities", "Bearer ", true)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getRawAuth("/api/v1/twin/entities", "Token "
                        + TestJwt.token(List.of("TWIN_READ")), true).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        String truncated = TestJwt.token(List.of("TWIN_READ"));
        truncated = truncated.substring(0, truncated.length() / 2);
        assertThat(getRawAuth("/api/v1/twin/entities", "Bearer " + truncated, true)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredAndNotYetValidTokensAre401() {
        assertThat(get("/api/v1/twin/entities", TestJwt.expiredToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/twin/entities", TestJwt.notYetValidToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongIssuerAudienceAndSignatureAre401() {
        assertThat(get("/api/v1/twin/entities", TestJwt.wrongIssuerToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/twin/entities", TestJwt.wrongAudienceToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/twin/entities", TestJwt.wrongSignatureToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void malformedAndUnknownRolesAre403() {
        assertThat(get("/api/v1/twin/entities",
                        TestJwt.malformedRoleToken(42)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/twin/entities",
                        TestJwt.malformedRoleToken(Map.of("role", "TWIN_READ")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/twin/entities",
                        TestJwt.token(List.of("SUPERUSER"))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/twin/entities",
                        TestJwt.token(List.of())).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void insufficientAuthorityIs403() {
        ResponseEntity<String> response = get("/api/v1/twin/entities",
                TestJwt.token(List.of("TWIN_ANALYZE")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("Access denied");
    }

    @Test
    void securityErrorsAreSafe() {
        String token = TestJwt.token(List.of("TWIN_READ"));
        ResponseEntity<String> denied = get("/api/v1/twin/entities",
                TestJwt.token(List.of("SUPERUSER")));
        for (ResponseEntity<String> response : List.of(
                get("/api/v1/twin/entities", null), denied)) {
            assertThat(response.getBody()).doesNotContain(token);
            assertThat(response.getBody()).doesNotContain("Bearer");
            assertThat(response.getBody()).doesNotContain("stackTrace");
            assertThat(response.getBody()).doesNotContain("at com.soubhagya");
            assertThat(response.getBody()).contains("\"path\"");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> notFound = http.exchange(url("/api/v1/twin/entities/"
                + java.util.UUID.randomUUID()), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
