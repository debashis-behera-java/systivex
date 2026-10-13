package com.soubhagya.systivex.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import com.soubhagya.systivex.twin.api.SystemEntityResponse;
import com.soubhagya.systivex.twin.api.SystemRelationshipResponse;
import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * HTTP surface of deterministic change-risk assessment over the real
 * Phase 4 graph: classification shape, runtime grounding, freshness
 * boundaries, uncertainty, validation parity with the impact endpoint,
 * determinism, read-only proof, and error privacy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class RiskApiTest extends AbstractPostgresIntegrationTest {

    private static final String INVENTORY_ITEMS = "target-table:inventory_db.inventory_items";

    @Autowired private TestRestTemplate rest;
    @Autowired private SystemEntityRepository entities;
    @Autowired private com.soubhagya.systivex.observation.TwinSyncService syncService;

    private Path realRoot;

    @BeforeEach
    void syncRealGraph() {
        realRoot = Path.of("").toAbsolutePath().resolve("target-services");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(realRoot), "real target-services tree not present");
        syncService.synchronize(realRoot);
    }

    private ResponseEntity<RiskResponse> riskRaw(String uri) {
        return rest.getForEntity(uri, RiskResponse.class);
    }

    private ResponseEntity<String> riskRawError(String uri) {
        return rest.getForEntity(uri, String.class);
    }

    private RiskResponse risk(String from) {
        ResponseEntity<RiskResponse> response =
                riskRaw("/api/v1/twin/risk?from=" + from);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static RiskResponse.RiskEntry entryBy(RiskResponse response, String externalRef) {
        return response.risks().stream()
                .filter(e -> externalRef.equals(e.externalRef()))
                .findFirst()
                .orElse(null);
    }

    private void setRuntime(
            String externalRef,
            String observedAt,
            boolean available,
            String status,
            Map<String, Long> counters) {
        SystemEntity entity = entities.findByExternalRef(externalRef).orElseThrow();
        Map<String, Object> metadata = new HashMap<>(entity.getMetadata());
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("available", available);
        health.put("status", status);
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("managedBy", "telemetry-connector");
        runtime.put("connectorVersion", "phase6-v1");
        runtime.put("observedAt", observedAt);
        runtime.put("health", health);
        runtime.put("counters", new LinkedHashMap<>(counters));
        runtime.put("limitations", List.of());
        metadata.put("runtime", runtime);
        entity.setMetadata(metadata);
        entities.save(entity);
    }

    private void clearAllRuntime() {
        for (SystemEntity entity : entities.findAll()) {
            if (entity.getMetadata() != null && entity.getMetadata().containsKey("runtime")) {
                Map<String, Object> metadata = new HashMap<>(entity.getMetadata());
                metadata.remove("runtime");
                entity.setMetadata(metadata);
                entities.save(entity);
            }
        }
    }

    private void freshUpAll() {
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        setRuntime("target-service:gateway-service", now, true, "UP", Map.of());
        setRuntime("target-service:order-service", now, true, "UP", Map.of());
        setRuntime("target-service:payment-service", now, true, "UP", Map.of());
        setRuntime("target-service:inventory-service", now, true, "UP", Map.of());
    }

    @Test
    void riskResponseShapeAndTaxonomy() {
        freshUpAll();
        RiskResponse response = risk(INVENTORY_ITEMS);

        assertThat(response.startedFrom()).hasSize(1);
        assertThat(response.startedFrom().get(0).externalRef()).isEqualTo(INVENTORY_ITEMS);
        assertThat(response.taxonomyVersion()).isEqualTo("risk-v1");
        assertThat(response.note()).contains("never authorizes");
        assertThat(response.risks()).isNotEmpty();
        for (RiskResponse.RiskEntry entry : response.risks()) {
            assertThat(entry.level()).isNotNull();
            assertThat(entry.reasons()).isNotEmpty();
            assertThat(new ArrayList<>(entry.reasons())).isSorted();
        }
        // Starts are never assessed.
        assertThat(entryBy(response, INVENTORY_ITEMS)).isNull();
    }

    @Test
    void freshHealthyGraphHasNoHighOrUnknown() {
        freshUpAll();
        RiskResponse response = risk(INVENTORY_ITEMS);

        assertThat(response.risks()).noneMatch(e -> e.level() == RiskLevel.HIGH);
        assertThat(response.risks()).noneMatch(e -> e.level() == RiskLevel.UNKNOWN);
        assertThat(response.risks()).noneMatch(RiskResponse.RiskEntry::uncertain);
        assertThat(response.risks()).noneMatch(RiskResponse.RiskEntry::stale);
    }

    @Test
    void downServiceIsHigh() {
        freshUpAll();
        setRuntime(
                "target-service:order-service",
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                true,
                "DOWN",
                Map.of());

        RiskResponse response = risk(INVENTORY_ITEMS);
        RiskResponse.RiskEntry order = entryBy(response, "target-service:order-service");

        assertThat(order).isNotNull();
        assertThat(order.level()).isEqualTo(RiskLevel.HIGH);
        assertThat(String.join(" ", order.reasons())).contains("DOWN");
        assertThat(order.uncertain()).isFalse();
        assertThat(order.stale()).isFalse();
    }

    @Test
    void failureCountersRaiseWithLifetimeWording() {
        freshUpAll();
        setRuntime(
                "target-service:inventory-service",
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                true,
                "UP",
                Map.of("inventory.reservation.rejected", 2L));

        RiskResponse response = risk(INVENTORY_ITEMS);
        RiskResponse.RiskEntry inventory =
                entryBy(response, "target-service:inventory-service");

        // Depth 1 starts MEDIUM; one recorded rejection raises to HIGH.
        assertThat(inventory.level()).isEqualTo(RiskLevel.HIGH);
        assertThat(String.join(" | ", inventory.reasons()))
                .contains("inventory.reservation.rejected=2")
                .contains("lifetime evidence, not incident proof");
    }

    @Test
    void staleSevereSignalRetainsHighAndFlags() {
        freshUpAll();
        setRuntime(
                "target-service:order-service",
                Instant.now().minus(2, ChronoUnit.HOURS).toString(),
                true,
                "DOWN",
                Map.of());

        RiskResponse response = risk(INVENTORY_ITEMS);
        RiskResponse.RiskEntry order = entryBy(response, "target-service:order-service");

        assertThat(order.level()).isEqualTo(RiskLevel.HIGH);
        assertThat(order.stale()).isTrue();
        assertThat(order.uncertain()).isTrue();
        assertThat(String.join(" ", order.reasons())).contains("DOWN").contains("stale");
    }

    @Test
    void missingRuntimeIsUnknown() {
        clearAllRuntime();

        RiskResponse response = risk(INVENTORY_ITEMS);

        for (String service :
                List.of(
                        "target-service:gateway-service",
                        "target-service:order-service",
                        "target-service:payment-service",
                        "target-service:inventory-service")) {
            RiskResponse.RiskEntry entry = entryBy(response, service);
            assertThat(entry).isNotNull();
            assertThat(entry.level()).isEqualTo(RiskLevel.UNKNOWN);
            assertThat(entry.uncertain()).isTrue();
        }
    }

    @Test
    void highRisksSortBeforeLowRisks() {
        freshUpAll();
        setRuntime(
                "target-service:order-service",
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                true,
                "DOWN",
                Map.of());

        RiskResponse response = risk(INVENTORY_ITEMS);
        List<String> levels =
                response.risks().stream().map(e -> e.level().name()).toList();

        assertThat(levels.indexOf("HIGH")).isLessThan(levels.indexOf("LOW"));
    }

    @Test
    void repeatAssessmentIsStable() {
        freshUpAll();
        RiskResponse first = risk(INVENTORY_ITEMS);
        RiskResponse second = risk(INVENTORY_ITEMS);

        assertThat(stable(second)).isEqualTo(stable(first));
    }

    private static String stable(RiskResponse response) {
        StringBuilder rendered = new StringBuilder();
        rendered.append(response.taxonomyVersion());
        for (RiskResponse.RiskEntry entry : response.risks()) {
            rendered.append(entry.id())
                    .append(entry.level())
                    .append(entry.reasons())
                    .append(entry.uncertain())
                    .append(entry.stale());
        }
        return rendered.toString();
    }

    @Test
    void missingFromReturns400() {
        assertThat(riskRawError("/api/v1/twin/risk").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownReferenceReturns404() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/twin/risk?from=target-service:no-such-service",
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void maxDepthOutOfRangeReturns400() {
        assertThat(riskRawError("/api/v1/twin/risk?from=" + INVENTORY_ITEMS + "&maxDepth=0")
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(riskRawError("/api/v1/twin/risk?from=" + INVENTORY_ITEMS + "&maxDepth=11")
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void errorBodiesLeakNothing() {
        ResponseEntity<String> missing = rest.getForEntity("/api/v1/twin/risk", String.class);
        ResponseEntity<String> unknown =
                rest.getForEntity("/api/v1/twin/risk?from=target-service:no-such-service",
                        String.class);
        for (ResponseEntity<String> response : List.of(missing, unknown)) {
            assertThat(response.getBody()).doesNotContain("password", "stackTrace", "SQLException");
            assertThat(response.getBody()).doesNotContain("at com.soubhagya");
        }
    }

    @Test
    void assessmentsDoNotMutateGraph() {
        int entitiesBefore = entityCount();
        int relationshipsBefore = relationshipCount();

        freshUpAll();
        risk(INVENTORY_ITEMS);
        risk(INVENTORY_ITEMS);

        assertThat(entityCount()).isEqualTo(entitiesBefore);
        assertThat(relationshipCount()).isEqualTo(relationshipsBefore);
    }

    @Test
    void riskMetadataWritesAreOutsideAssessment() {
        // The assessment itself writes nothing: metadata changes in this
        // test come only from the explicit fixture setup above it.
        freshUpAll();
        int entitiesBefore = entityCount();
        risk(INVENTORY_ITEMS);
        assertThat(entityCount()).isEqualTo(entitiesBefore);
    }

    private int entityCount() {
        ResponseEntity<SystemEntityResponse[]> response =
                rest.getForEntity("/api/v1/twin/entities", SystemEntityResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody() == null ? 0 : response.getBody().length;
    }

    private int relationshipCount() {
        ResponseEntity<SystemRelationshipResponse[]> response =
                rest.getForEntity(
                        "/api/v1/twin/relationships", SystemRelationshipResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody() == null ? 0 : response.getBody().length;
    }
}
