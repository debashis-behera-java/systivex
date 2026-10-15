package com.soubhagya.systivex.intelligence;

import static com.soubhagya.systivex.intelligence.SimulationApiSupport.GATEWAY;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.INVENTORY_ITEMS;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.addEdge;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.body;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.dropEdge;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.entityCount;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.orderCallsInventoryDrop;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.relationshipCount;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.removeEntity;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.stable;
import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.observation.TwinSyncService;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import com.soubhagya.systivex.twin.model.SystemEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
 * HTTP behaviour of deterministic change simulation over the real Phase 4
 * graph: before/after comparison, entity-removal cascades, deterministic
 * output, settings flow, uncertainty flags, read-only proof, and
 * impact/risk stability. Requires Docker (Testcontainers PostgreSQL, like
 * the other API suites); the validation matrix lives in
 * {@code SimulationProposalApiTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SimulationApiTest extends AbstractPostgresIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private TwinSyncService syncService;

    @Autowired private com.soubhagya.systivex.twin.repository.SystemEntityRepository entities;

    @BeforeEach
    void syncRealGraph() {
        Path realRoot = Path.of("").toAbsolutePath().resolve("target-services");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(realRoot), "real target-services tree not present");
        syncService.synchronize(realRoot);
    }

    private SimulationResponse simulate(Map<String, Object> body) {
        ResponseEntity<SimulationResponse> response =
                rest.postForEntity("/api/v1/twin/simulations", body, SimulationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static boolean stableScopes(SimulationResponse response) {
        return response.before().impact().impacted().stream()
                        .map(ImpactResponse.ImpactedEntity::id)
                        .sorted()
                        .toList()
                .equals(
                        response.after().impact().impacted().stream()
                                .map(ImpactResponse.ImpactedEntity::id)
                                .sorted()
                                .toList());
    }

    @Test
    void emptyProposalComparesIdenticalScopes() {
        SimulationResponse response =
                simulate(body(List.of(INVENTORY_ITEMS), null, null, List.of()));

        assertThat(response.simulated()).isTrue();
        assertThat(response.advisory()).contains("SIMULATED — NOT EXECUTED");
        assertThat(response.settings().rootCount()).isEqualTo(1);
        assertThat(response.settings().maxDepth()).isEqualTo(6);
        assertThat(stableScopes(response)).isTrue();
        assertThat(response.delta().impactedAddedCount()).isZero();
        assertThat(response.delta().impactedRemovedCount()).isZero();
        assertThat(response.graph().baselineEntityCount())
                .isEqualTo(response.graph().hypotheticalEntityCount());
        assertThat(response.graph().baselineRelationshipCount())
                .isEqualTo(response.graph().hypotheticalRelationshipCount());
        assertThat(String.join(" ", response.limitations()))
                .contains("SIMULATED — NOT EXECUTED")
                .contains("no proposal")
                .contains("Runtime telemetry is reused unchanged")
                .contains("Caps and truncation may hide");
    }

    @Test
    void missingOperationsComparesIdenticalScopes() {
        SimulationResponse response =
                simulate(body(List.of(INVENTORY_ITEMS), null, null, null));

        assertThat(response.simulated()).isTrue();
        assertThat(stableScopes(response)).isTrue();
    }

    @Test
    void entityRemovalCascadesAndShrinksImpact() {
        // gateway-service reaches inventory_items in the baseline; removing
        // the table plus its incident edges must drop it from the
        // hypothetical impact.
        SimulationResponse response =
                simulate(
                        body(
                                List.of(GATEWAY),
                                null,
                                null,
                                List.of(removeEntity(INVENTORY_ITEMS))));

        assertThat(response.simulated()).isTrue();
        assertThat(response.graph().hypotheticalEntityCount())
                .isEqualTo(response.graph().baselineEntityCount() - 1);
        assertThat(response.graph().removedEntityCount()).isEqualTo(1);
        assertThat(response.graph().hypotheticalRelationshipCount())
                .isLessThan(response.graph().baselineRelationshipCount());
        assertThat(response.delta().impactedRemoved()).contains(INVENTORY_ITEMS);
        assertThat(
                        response.after().impact().impacted().stream()
                                .map(ImpactResponse.ImpactedEntity::externalRef))
                .doesNotContain(INVENTORY_ITEMS);
        assertThat(
                        response.before().impact().impacted().stream()
                                .map(ImpactResponse.ImpactedEntity::externalRef))
                .contains(INVENTORY_ITEMS);
    }

    @Test
    void relationshipRemovalShrinksImpactWithoutRemovingEntities() {
        // order-service CALLS inventory-service exists in the Phase 4 graph:
        // dropping it from the hypothetical graph keeps every entity while
        // narrowing reachability.
        SimulationResponse response =
                simulate(
                        body(
                                List.of(GATEWAY),
                                null,
                                null,
                                List.of(orderCallsInventoryDrop())));

        assertThat(response.graph().hypotheticalEntityCount())
                .isEqualTo(response.graph().baselineEntityCount());
        assertThat(response.graph().removedRelationshipCount()).isEqualTo(1);
        assertThat(response.delta().afterImpactedCount())
                .isLessThanOrEqualTo(response.delta().beforeImpactedCount());
    }

    @Test
    void edgeAdditionGrowsHypotheticalGraph() {
        SimulationResponse response =
                simulate(
                        body(
                                List.of(GATEWAY),
                                null,
                                null,
                                List.of(
                                        addEdge(
                                                "target-service:payment-service",
                                                "target-service:inventory-service",
                                                "CALLS"))));

        assertThat(response.graph().hypotheticalRelationshipCount())
                .isEqualTo(response.graph().baselineRelationshipCount() + 1);
        assertThat(response.graph().addedRelationshipCount()).isEqualTo(1);
        assertThat(response.delta().afterImpactedCount())
                .isGreaterThanOrEqualTo(response.delta().beforeImpactedCount());
    }

    @Test
    void repeatSimulationIsDeterministic() {
        Map<String, Object> request =
                body(List.of(GATEWAY), null, null, List.of(removeEntity(INVENTORY_ITEMS)));

        assertThat(stable(simulate(request))).isEqualTo(stable(simulate(request)));
    }

    @Test
    void maxDepthAndIncludeTypesFlowIntoBothScopes() {
        SimulationResponse response =
                simulate(body(List.of(INVENTORY_ITEMS), 1, List.of("SERVICE"), List.of()));

        assertThat(response.settings().maxDepth()).isEqualTo(1);
        assertThat(response.before().impact().maxDepth()).isEqualTo(1);
        assertThat(response.after().impact().maxDepth()).isEqualTo(1);
        assertThat(response.before().impact().impacted())
                .allMatch(e -> e.depth() == 1 && e.type().name().equals("SERVICE"));
        assertThat(stableScopes(response)).isTrue();
    }

    @Test
    void uncertainRuntimeSurfacesInLimitations() {
        // No runtime metadata anywhere: every impacted service is UNKNOWN
        // and uncertain, and the limitations say so explicitly.
        for (SystemEntity entity : entities.findAll()) {
            if (entity.getMetadata() != null && entity.getMetadata().containsKey("runtime")) {
                Map<String, Object> metadata = new HashMap<>(entity.getMetadata());
                metadata.remove("runtime");
                entity.setMetadata(metadata);
                entities.save(entity);
            }
        }

        SimulationResponse response =
                simulate(body(List.of(INVENTORY_ITEMS), null, null, List.of()));

        assertThat(response.delta().uncertainBeforeCount()).isGreaterThan(0);
        assertThat(String.join(" ", response.limitations())).contains("uncertain");
    }

    @Test
    void simulationsDoNotMutateGraph() {
        int entitiesBefore = entityCount(rest);
        int relationshipsBefore = relationshipCount(rest);

        simulate(body(List.of(GATEWAY), null, null, List.of(removeEntity(INVENTORY_ITEMS))));
        simulate(body(List.of(GATEWAY), null, null, List.of(orderCallsInventoryDrop())));
        rest.postForEntity(
                "/api/v1/twin/simulations",
                body(List.of(GATEWAY), null, null, List.of(removeEntity(GATEWAY))),
                String.class);
        rest.postForEntity(
                "/api/v1/twin/simulations",
                body(List.of(GATEWAY), null, null,
                        List.of(removeEntity("target-service:nope"))),
                String.class);

        assertThat(entityCount(rest)).isEqualTo(entitiesBefore);
        assertThat(relationshipCount(rest)).isEqualTo(relationshipsBefore);
    }

    @Test
    void impactAndRiskEndpointsStillWork() {
        simulate(body(List.of(GATEWAY), null, null, List.of(removeEntity(INVENTORY_ITEMS))));

        ResponseEntity<ImpactResponse> impact =
                rest.getForEntity(
                        "/api/v1/twin/impact?from=" + INVENTORY_ITEMS, ImpactResponse.class);
        assertThat(impact.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(impact.getBody()).isNotNull();
        assertThat(impact.getBody().impacted()).isNotEmpty();

        ResponseEntity<RiskResponse> risk =
                rest.getForEntity(
                        "/api/v1/twin/risk?from=" + INVENTORY_ITEMS, RiskResponse.class);
        assertThat(risk.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(risk.getBody()).isNotNull();
        assertThat(risk.getBody().taxonomyVersion()).isEqualTo("risk-v1");
    }

    @Test
    void errorBodiesLeakNothing() {
        for (ResponseEntity<String> response : List.of(
                rest.postForEntity(
                        "/api/v1/twin/simulations",
                        body(null, null, null, List.of()), String.class),
                rest.postForEntity(
                        "/api/v1/twin/simulations",
                        body(List.of(GATEWAY), null, null, List.of(removeEntity(GATEWAY))),
                        String.class))) {
            assertThat(response.getBody())
                    .doesNotContain("password", "stackTrace", "SQLException");
            assertThat(response.getBody()).doesNotContain("at com.soubhagya");
        }
    }
}
