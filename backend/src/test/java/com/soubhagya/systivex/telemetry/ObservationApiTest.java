package com.soubhagya.systivex.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.soubhagya.systivex.observation.TwinSyncService;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import com.soubhagya.systivex.twin.api.SystemEntityResponse;
import com.soubhagya.systivex.twin.api.SystemRelationshipResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP surface of the observation trigger: success over loopback with a
 * bounded receipt, failure rollback without writes, 403 off loopback, and
 * error-response privacy. Uses a fake loopback source — no live services.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@AutoConfigureMockMvc
class ObservationApiTest extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class FakeSourceConfig {
        static final Map<Integer, TelemetrySource.Snapshot> SNAPSHOTS = new HashMap<>();

        @Bean
        @Primary
        TelemetrySource fakeTelemetrySource() {
            return SNAPSHOTS::get;
        }
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private MockMvc mockMvc;
    @Autowired private TwinSyncService syncService;

    private Path realRoot;

    @BeforeEach
    void setUp() {
        realRoot = Path.of("").toAbsolutePath().resolve("target-services");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(realRoot), "real target-services tree not present");
        FakeSourceConfig.SNAPSHOTS.clear();
        FakeSourceConfig.SNAPSHOTS.put(8081, new TelemetrySource.Snapshot(
                "{\"status\":\"UP\"}", "http_server_requests_seconds_count 9\n"));
        FakeSourceConfig.SNAPSHOTS.put(8082, new TelemetrySource.Snapshot(
                "{\"status\":\"UP\"}",
                "checkout_success_total 2\ncheckout_failure_total 1\n"));
        FakeSourceConfig.SNAPSHOTS.put(8083, new TelemetrySource.Snapshot(
                "{\"status\":\"UP\"}",
                "payment_authorization_success_total 5\npayment_authorization_declined_total 1\n"));
        FakeSourceConfig.SNAPSHOTS.put(8084, new TelemetrySource.Snapshot(
                "{\"status\":\"UP\"}",
                "inventory_reservation_success_total 4\ninventory_reservation_rejected_total 0\n"));
        syncService.synchronize(realRoot);
    }

    @Test
    void observeOverLoopbackReturnsReceipt() {
        ResponseEntity<ObservationReceipt> response =
                rest.postForEntity(
                        "/api/v1/twin/observations",
                        new ObservationController.ObserveRequest(),
                        ObservationReceipt.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().servicesProcessed()).isEqualTo(4);
        assertThat(response.getBody().factsUpdated()
                        + response.getBody().factsUnchanged())
                .isEqualTo(4);
        assertThat(response.getBody().observedAt()).isNotNull();
        assertThat(response.getBody().limitations()).isNotEmpty();
        assertThat(response.getBody().note()).contains("never inferred");
    }

    @Test
    void observeWithEmptyBodyReturnsReceipt() {
        ResponseEntity<ObservationReceipt> response =
                rest.postForEntity("/api/v1/twin/observations", null, ObservationReceipt.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().servicesProcessed()).isEqualTo(4);
    }

    @Test
    void failedObservationReturns400WithoutWrites() {
        FakeSourceConfig.SNAPSHOTS.put(8082, new TelemetrySource.Snapshot(
                "{\"status\":\"UP\"}", "checkout_success_total{leak=\"x\"} 2\n"));

        int before = entityCount();
        ResponseEntity<String> response =
                rest.postForEntity(
                        "/api/v1/twin/observations",
                        new ObservationController.ObserveRequest(),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entityCount()).isEqualTo(before);
        assertThat(response.getBody()).doesNotContain("password", "stackTrace", "SQLException");
        assertThat(response.getBody()).doesNotContain("at com.soubhagya");
    }

    @Test
    void observeOffLoopbackIsForbidden() throws Exception {
        mockMvc.perform(
                        post("/api/v1/twin/observations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                                .with(request -> {
                                    request.setRemoteAddr("203.0.113.9");
                                    return request;
                                }))
                .andExpect(status().isForbidden());
    }

    @Test
    void observationDoesNotChangeGraphSize() {
        int entitiesBefore = entityCount();
        int relationshipsBefore = relationshipCount();

        rest.postForEntity(
                "/api/v1/twin/observations",
                new ObservationController.ObserveRequest(),
                ObservationReceipt.class);

        assertThat(entityCount()).isEqualTo(entitiesBefore);
        assertThat(relationshipCount()).isEqualTo(relationshipsBefore);
    }

    private int entityCount() {
        ResponseEntity<SystemEntityResponse[]> response =
                rest.getForEntity("/api/v1/twin/entities", SystemEntityResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody() == null ? 0 : response.getBody().length;
    }

    private int relationshipCount() {
        ResponseEntity<SystemRelationshipResponse[]> response =
                rest.getForEntity("/api/v1/twin/relationships", SystemRelationshipResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody() == null ? 0 : response.getBody().length;
    }
}
