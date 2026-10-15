package com.soubhagya.systivex.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.security.TestJwt;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import com.soubhagya.systivex.twin.api.SystemEntityResponse;
import com.soubhagya.systivex.twin.api.SystemRelationshipResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP surface of the sync trigger: success over loopback, 400 on a bad
 * root without partial writes, 403 off loopback, and the resulting graph
 * visible through the existing GET endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@AutoConfigureMockMvc
class SyncApiTest extends AbstractPostgresIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private MockMvc mockMvc;

    private Path realRoot;

    @BeforeEach
    void resolveRealRoot() {
        realRoot = Path.of("").toAbsolutePath().resolve("target-services");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(realRoot), "real target-services tree not present");
    }

    @Test
    void syncOverLoopbackReturnsCounts() {
        ResponseEntity<SyncResult> response =
                rest.postForEntity(
                        "/api/v1/twin/sync",
                        new SyncController.SyncRequest(realRoot.toString()),
                        SyncResult.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // The Testcontainers database is shared JVM-wide, so an earlier test
        // class may already have synced: the discovered total must be exact.
        assertThat(
                        response.getBody().entitiesCreated()
                                + response.getBody().entitiesUnchanged())
                .isEqualTo(15);
        assertThat(
                        response.getBody().relationshipsCreated()
                                + response.getBody().relationshipsUnchanged())
                .isEqualTo(18);
    }

    @Test
    void syncWithDefaultRootResolvesLocally() {
        // No body: resolution falls back to the working-directory default,
        // which is backend/target-services when tests run from backend/.
        ResponseEntity<SyncResult> response =
                rest.postForEntity("/api/v1/twin/sync", null, SyncResult.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().entitiesCreated() + response.getBody().entitiesUnchanged())
                .isEqualTo(15);
    }

    @Test
    void syncWithBadRootReturns400WithoutWrites() {
        ResponseEntity<SystemEntityResponse[]> before =
                rest.getForEntity("/api/v1/twin/entities", SystemEntityResponse[].class);
        int entityCount =
                before.getBody() == null ? 0 : Arrays.asList(before.getBody()).size();

        ResponseEntity<String> response =
                rest.postForEntity(
                        "/api/v1/twin/sync",
                        new SyncController.SyncRequest(realRoot.resolve("nope").toString()),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<SystemEntityResponse[]> after =
                rest.getForEntity("/api/v1/twin/entities", SystemEntityResponse[].class);
        assertThat(after.getBody() == null ? 0 : Arrays.asList(after.getBody()).size())
                .isEqualTo(entityCount);
    }

    @Test
    void syncOffLoopbackIsForbidden() throws Exception {
        // Valid operator token: authentication and role checks pass, so the
        // 403 proves the loopback guard stays authoritative behind auth.
        String operatorToken = TestJwt.token(java.util.List.of("TWIN_OPERATOR"));
        mockMvc.perform(
                        post("/api/v1/twin/sync")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + operatorToken)
                                .content("{}")
                                .with(request -> {
                                    request.setRemoteAddr("203.0.113.9");
                                    return request;
                                }))
                .andExpect(status().isForbidden());
    }

    @Test
    void syncedGraphVisibleThroughExistingEndpoints() {
        rest.postForEntity(
                "/api/v1/twin/sync",
                new SyncController.SyncRequest(realRoot.toString()),
                SyncResult.class);

        ResponseEntity<SystemEntityResponse[]> entitiesResponse =
                rest.getForEntity("/api/v1/twin/entities", SystemEntityResponse[].class);
        assertThat(entitiesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entitiesResponse.getBody()).isNotNull();
        assertThat(
                        Arrays.stream(entitiesResponse.getBody())
                                .filter(e -> "target-service:order-service".equals(e.externalRef()))
                                .findFirst())
                .isPresent();

        ResponseEntity<SystemRelationshipResponse[]> relationshipsResponse =
                rest.getForEntity("/api/v1/twin/relationships", SystemRelationshipResponse[].class);
        assertThat(relationshipsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(relationshipsResponse.getBody()).isNotNull();
        assertThat(relationshipsResponse.getBody().length).isGreaterThanOrEqualTo(18);
    }
}
