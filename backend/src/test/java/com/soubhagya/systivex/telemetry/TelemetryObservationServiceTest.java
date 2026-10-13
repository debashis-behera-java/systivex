package com.soubhagya.systivex.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.soubhagya.systivex.intelligence.ImpactAnalysisService;
import com.soubhagya.systivex.intelligence.ImpactResponse;
import com.soubhagya.systivex.observation.OwnershipConflictException;
import com.soubhagya.systivex.observation.TwinSyncService;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import com.soubhagya.systivex.twin.api.CreateSystemEntityRequest;
import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import com.soubhagya.systivex.twin.repository.SystemRelationshipRepository;
import com.soubhagya.systivex.twin.service.TwinService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Transactional behaviour of telemetry observation against throwaway
 * PostgreSQL: successful annotation, idempotency/timestamp contract,
 * manual-row preservation, unknown mappings, privacy rollback, and Phase
 * 5 impact stability. Uses a fake in-memory source — no live collector.
 */
@SpringBootTest
class TelemetryObservationServiceTest extends AbstractPostgresIntegrationTest {

    private static final String ORDER_ITEMS = "target-table:inventory_db.inventory_items";

    @Autowired private TwinSyncService syncService;
    @Autowired private TwinService twinService;
    @Autowired private SystemEntityRepository entities;
    @Autowired private SystemRelationshipRepository relationships;
    @Autowired private ImpactAnalysisService impactService;

    private Path realRoot;
    private final Map<Integer, TelemetrySource.Snapshot> snapshots = new HashMap<>();

    private TelemetryObservationService service() {
        return new TelemetryObservationService(entities, port -> snapshots.get(port));
    }

    @BeforeEach
    void setUp() {
        realRoot = Path.of("").toAbsolutePath().resolve("target-services");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(realRoot), "real target-services tree not present");
        relationships.deleteAll();
        entities.deleteAll();
        snapshots.clear();
        snapshots.put(8081, new TelemetrySource.Snapshot(
                "{\"status\":\"UP\"}", "http_server_requests_seconds_count 9\n"));
        snapshots.put(8082, new TelemetrySource.Snapshot(
                "{\"status\":\"UP\"}",
                "checkout_success_total 2\ncheckout_failure_total 1\n"));
        snapshots.put(8083, new TelemetrySource.Snapshot(
                "{\"status\":\"UP\"}",
                "payment_authorization_success_total 5\npayment_authorization_declined_total 0\n"));
        snapshots.put(8084, new TelemetrySource.Snapshot(
                "{\"status\":\"UP\"}",
                "inventory_reservation_success_total 4\ninventory_reservation_rejected_total 1\n"));
        syncService.synchronize(realRoot);
    }

    @Test
    void successfulObservationAnnotatesManagedServices() {
        ObservationReceipt receipt = service().observe();

        assertThat(receipt.servicesProcessed()).isEqualTo(4);
        assertThat(receipt.factsUpdated()).isEqualTo(4);
        assertThat(receipt.factsUnchanged()).isZero();
        assertThat(receipt.observedAt()).isNotNull();
        assertThat(receipt.note()).contains("never inferred");

        SystemEntity order =
                entities.findByExternalRef("target-service:order-service").orElseThrow();
        assertThat(order.getMetadata()).containsKey(TelemetryConnector.RUNTIME_KEY);
        assertThat(order.getMetadata()).containsEntry("managedBy", "repository-connector");
        assertThat(order.getMetadata()).containsKey("port");

        ServiceObservation orderObs = serviceObservation(receipt, "target-service:order-service");
        assertThat(orderObs.healthAvailable()).isTrue();
        assertThat(orderObs.healthStatus()).isEqualTo("UP");
        assertThat(orderObs.counters())
                .containsEntry("checkout.success", 2L)
                .containsEntry("checkout.failure", 1L);
        assertThat(orderObs.factsChanged()).isTrue();

        ServiceObservation gatewayObs =
                serviceObservation(receipt, "target-service:gateway-service");
        assertThat(gatewayObs.counters()).isEmpty();
        assertThat(gatewayObs.limitations()).anyMatch(l -> l.contains("health only"));
    }

    @Test
    void repeatObservationWithIdenticalFactsReportsUnchangedButRefreshesTimestamp() {
        ObservationReceipt first = service().observe();
        ObservationReceipt second = service().observe();

        assertThat(second.factsUpdated()).isZero();
        assertThat(second.factsUnchanged()).isEqualTo(4);
        assertThat(second.observedAt()).isAfterOrEqualTo(first.observedAt());
        // Staleness is never hidden: the stored timestamp advances with the run.
        SystemEntity order =
                entities.findByExternalRef("target-service:order-service").orElseThrow();
        assertThat(storedObservedAt(order)).isEqualTo(second.observedAt().toString());
    }

    @Test
    void changedCountersReportUpdated() {
        service().observe();
        snapshots.put(8082, new TelemetrySource.Snapshot(
                "{\"status\":\"UP\"}",
                "checkout_success_total 3\ncheckout_failure_total 1\n"));

        ObservationReceipt second = service().observe();

        assertThat(second.factsUpdated()).isEqualTo(1);
        assertThat(second.factsUnchanged()).isEqualTo(3);
        assertThat(serviceObservation(second, "target-service:order-service").factsChanged())
                .isTrue();
    }

    @Test
    void unavailableServiceIsRecordedWithLimitations() {
        snapshots.put(8084, new TelemetrySource.Snapshot(null, null));

        ObservationReceipt receipt = service().observe();

        assertThat(receipt.servicesProcessed()).isEqualTo(4);
        ServiceObservation inventory =
                serviceObservation(receipt, "target-service:inventory-service");
        assertThat(inventory.healthAvailable()).isFalse();
        assertThat(inventory.counters()).isEmpty();
        assertThat(inventory.limitations()).isNotEmpty();
        // Missing evidence is never inferred as values.
        SystemEntity entity =
                entities.findByExternalRef("target-service:inventory-service").orElseThrow();
        assertThat(storedCounters(entity)).isEmpty();
    }

    @Test
    void absentCounterFamiliesAreNotInferredAsZero() {
        snapshots.put(8082, new TelemetrySource.Snapshot(
                "{\"status\":\"UP\"}", "checkout_success_total 2\n"));

        ObservationReceipt receipt = service().observe();

        ServiceObservation order = serviceObservation(receipt, "target-service:order-service");
        assertThat(order.counters()).containsOnlyKeys("checkout.success");
        assertThat(order.limitations()).anyMatch(l -> l.contains("checkout.failure"));
    }

    @Test
    void manualRowsArePreserved() {
        twinService.createEntity(
                new CreateSystemEntityRequest(
                        SystemEntityType.SERVICE,
                        "hand-drawn",
                        "manual:hand-drawn",
                        "dev",
                        Map.of("owner", "operator")));

        ObservationReceipt receipt = service().observe();

        assertThat(receipt.servicesProcessed()).isEqualTo(4);
        SystemEntity manual = entities.findByExternalRef("manual:hand-drawn").orElseThrow();
        assertThat(manual.getMetadata())
                .containsEntry("owner", "operator")
                .doesNotContainKey(TelemetryConnector.RUNTIME_KEY);
    }

    @Test
    void unknownServiceMappingFailsWithoutWrites() {
        twinService.createEntity(
                new CreateSystemEntityRequest(
                        SystemEntityType.SERVICE,
                        "mystery",
                        "target-service:mystery-service",
                        "dev",
                        Map.of("managedBy", "repository-connector", "port", 8099)));

        assertThatThrownBy(() -> service().observe())
                .isInstanceOf(OwnershipConflictException.class);

        assertThat(entities.findAll())
                .filteredOn(e -> e.getMetadata().containsKey(TelemetryConnector.RUNTIME_KEY))
                .isEmpty();
    }

    @Test
    void missingPortFailsWithoutWrites() {
        SystemEntity gateway = entities.findByExternalRef("target-service:gateway-service")
                .orElseThrow();
        Map<String, Object> metadata = new HashMap<>(gateway.getMetadata());
        metadata.remove("port");
        gateway.setMetadata(metadata);
        entities.save(gateway);

        assertThatThrownBy(() -> service().observe())
                .isInstanceOf(OwnershipConflictException.class);

        assertThat(entities.findAll())
                .filteredOn(e -> e.getMetadata().containsKey(TelemetryConnector.RUNTIME_KEY))
                .isEmpty();
    }

    @Test
    void privacyViolationRollsBackWholeRun() {
        snapshots.put(8083, new TelemetrySource.Snapshot(
                "{\"status\":\"UP\"}",
                "payment_authorization_success_total{outcome=\"ok\"} 5\n"));

        assertThatThrownBy(() -> service().observe())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unexpected labels");

        assertThat(entities.findAll())
                .filteredOn(e -> e.getMetadata().containsKey(TelemetryConnector.RUNTIME_KEY))
                .isEmpty();
    }

    @Test
    void emptyTwinFailsWith404() {
        relationships.deleteAll();
        entities.deleteAll();

        assertThatThrownBy(() -> service().observe())
                .isInstanceOf(
                        com.soubhagya.systivex.twin.service.EntityNotFoundException.class);
    }

    @Test
    void observationLeavesRelationshipsAndImpactSemanticsUnchanged() {
        service().observe();

        assertThat(entities.count()).isEqualTo(15);
        assertThat(relationships.count()).isEqualTo(18);

        ImpactResponse impact = impactService.analyze(List.of(ORDER_ITEMS), null, null);
        assertThat(impact.startedFrom()).hasSize(1);
        assertThat(impact.impacted())
                .anyMatch(e -> "target-service:inventory-service".equals(e.externalRef()));
        assertThat(impact.cycleEncountered()).isFalse();
        assertThat(impact.paths()).isNotEmpty();
    }

    @Test
    void resyncPreservesRuntimeAnnotations() {
        service().observe();
        var resync = syncService.synchronize(realRoot);

        assertThat(resync.entitiesUpdated()).isZero();
        assertThat(resync.entitiesUnchanged()).isEqualTo(15);
        SystemEntity order =
                entities.findByExternalRef("target-service:order-service").orElseThrow();
        assertThat(order.getMetadata()).containsKey(TelemetryConnector.RUNTIME_KEY);
    }

    private static ServiceObservation serviceObservation(
            ObservationReceipt receipt, String externalRef) {
        return receipt.services().stream()
                .filter(s -> externalRef.equals(s.externalRef()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing observation for " + externalRef));
    }

    @SuppressWarnings("unchecked")
    private static String storedObservedAt(SystemEntity entity) {
        Map<String, Object> runtime =
                (Map<String, Object>) entity.getMetadata().get(TelemetryConnector.RUNTIME_KEY);
        return String.valueOf(runtime.get("observedAt"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> storedCounters(SystemEntity entity) {
        Map<String, Object> runtime =
                (Map<String, Object>) entity.getMetadata().get(TelemetryConnector.RUNTIME_KEY);
        return (Map<String, Object>) runtime.get("counters");
    }
}
