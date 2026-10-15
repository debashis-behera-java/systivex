package com.soubhagya.systivex.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.intelligence.ImpactResponse;
import com.soubhagya.systivex.intelligence.RiskResponse;
import com.soubhagya.systivex.intelligence.SimulationOperation;
import com.soubhagya.systivex.intelligence.SimulationOperationType;
import com.soubhagya.systivex.intelligence.SimulationRequest;
import com.soubhagya.systivex.observation.TwinSyncService;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import com.soubhagya.systivex.twin.api.SystemEntityResponse;
import com.soubhagya.systivex.twin.api.SystemRelationshipResponse;
import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * HTTP surface of advisory policy evaluation over the real Phase 4 graph:
 * decision matrix, validation parity with simulation (400/404/409),
 * determinism, stale/unknown evidence, read-only proof, leakage, and
 * impact/risk/simulation stability. Requires Docker (Testcontainers
 * PostgreSQL, like the other API suites).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PolicyApiTest extends AbstractPostgresIntegrationTest {

    private static final String GATEWAY = "target-service:gateway-service";
    private static final String ORDER = "target-service:order-service";
    private static final String INVENTORY_ITEMS = "target-table:inventory_db.inventory_items";

    @Autowired private TestRestTemplate rest;
    @Autowired private TwinSyncService syncService;
    @Autowired private SystemEntityRepository entities;

    @BeforeEach
    void syncRealGraph() {
        Path realRoot = Path.of("").toAbsolutePath().resolve("target-services");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(realRoot), "real target-services tree not present");
        syncService.synchronize(realRoot);
    }

    private ResponseEntity<PolicyEvaluationResponse> evaluateRaw(SimulationRequest body) {
        return rest.postForEntity(
                "/api/v1/twin/policy-evaluations", body, PolicyEvaluationResponse.class);
    }

    private PolicyEvaluationResponse evaluate(SimulationRequest body) {
        ResponseEntity<PolicyEvaluationResponse> response = evaluateRaw(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static SimulationRequest proposal(
            List<String> from, Integer maxDepth, List<String> includeTypes,
            List<SimulationOperation> operations) {
        return new SimulationRequest(from, maxDepth, includeTypes, operations);
    }

    private static SimulationOperation removeEntity(String entity) {
        return new SimulationOperation(
                SimulationOperationType.REMOVE_ENTITY, entity, null, null, null);
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

    private static List<String> ruleCodes(PolicyEvaluationResponse response) {
        return response.rulesFired().stream()
                .map(com.soubhagya.systivex.policy.PolicyEvaluator.FiredRule::code)
                .toList();
    }

    private static String stable(PolicyEvaluationResponse response) {
        return response.decision().name()
                + response.policyVersion()
                + response.rulesFired()
                + response.simulation().delta()
                + response.simulation().graph();
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

    @Test
    void emptyProposalOnHealthyGraphAllows() {
        freshUpAll();

        PolicyEvaluationResponse response =
                evaluate(proposal(List.of(INVENTORY_ITEMS), null, null, List.of()));

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(response.policyVersion()).isEqualTo("policy-v1");
        assertThat(response.advisory()).contains("NOT AN AUTHORIZATION");
        assertThat(response.rulesFired()).isEmpty();
        assertThat(response.note()).contains("authorizes nothing");
        assertThat(response.simulation().limitations()).isNotEmpty();
    }

    @Test
    void missingRuntimeReviewsWithUnknownAndUncertain() {
        clearAllRuntime();

        PolicyEvaluationResponse response =
                evaluate(proposal(List.of(INVENTORY_ITEMS), null, null, List.of()));

        assertThat(response.decision()).isEqualTo(PolicyDecision.REVIEW);
        assertThat(ruleCodes(response))
                .contains(
                        PolicyTaxonomy.UNKNOWN_RISK_PRESENT, PolicyTaxonomy.EVIDENCE_UNCERTAIN);
    }

    @Test
    void downServiceDenies() {
        freshUpAll();
        setRuntime(
                ORDER,
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                true,
                "DOWN",
                Map.of());

        PolicyEvaluationResponse response =
                evaluate(proposal(List.of(INVENTORY_ITEMS), null, null, List.of()));

        assertThat(response.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(ruleCodes(response)).contains(PolicyTaxonomy.HIGH_RISK_REMAINS);
    }

    @Test
    void staleEvidenceReviews() {
        freshUpAll();
        setRuntime(
                ORDER,
                Instant.now().minus(2, ChronoUnit.HOURS).toString(),
                true,
                "UP",
                Map.of());

        PolicyEvaluationResponse response =
                evaluate(proposal(List.of(INVENTORY_ITEMS), null, null, List.of()));

        assertThat(response.decision()).isEqualTo(PolicyDecision.REVIEW);
        assertThat(ruleCodes(response)).contains(PolicyTaxonomy.EVIDENCE_UNCERTAIN);
    }

    @Test
    void entityRemovalEvaluatesAgainstHypotheticalScope() {
        freshUpAll();

        PolicyEvaluationResponse response =
                evaluate(
                        proposal(
                                List.of(GATEWAY),
                                6,
                                null,
                                List.of(removeEntity(INVENTORY_ITEMS))));

        assertThat(response.decision()).isNotNull();
        assertThat(response.simulation().delta().impactedRemoved())
                .contains(INVENTORY_ITEMS);
        assertThat(response.simulation().graph().hypotheticalEntityCount())
                .isEqualTo(response.simulation().graph().baselineEntityCount() - 1);
    }

    @Test
    void repeatEvaluationIsDeterministic() {
        freshUpAll();
        SimulationRequest body =
                proposal(List.of(GATEWAY), 6, null, List.of(removeEntity(INVENTORY_ITEMS)));

        assertThat(stable(evaluate(body))).isEqualTo(stable(evaluate(body)));
    }

    @Test
    void missingFromReturns400() {
        assertThat(
                        rest.postForEntity(
                                        "/api/v1/twin/policy-evaluations",
                                        Map.of("operations", List.of()),
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownReferenceReturns404() {
        assertThat(
                        rest.postForEntity(
                                        "/api/v1/twin/policy-evaluations",
                                        Map.of(
                                                "from",
                                                List.of("target-service:no-such-service"),
                                                "operations",
                                                List.of()),
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removingRootReturns409() {
        assertThat(
                        rest.postForEntity(
                                        "/api/v1/twin/policy-evaluations",
                                        Map.of(
                                                "from",
                                                List.of(GATEWAY),
                                                "operations",
                                                List.of(
                                                        Map.of(
                                                                "type", "REMOVE_ENTITY",
                                                                "entity", GATEWAY))),
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void evaluationsDoNotMutateGraph() {
        int entitiesBefore = entityCount();
        int relationshipsBefore = relationshipCount();

        freshUpAll();
        evaluate(proposal(List.of(GATEWAY), 6, null, List.of(removeEntity(INVENTORY_ITEMS))));
        evaluate(proposal(List.of(INVENTORY_ITEMS), null, null, List.of()));
        rest.postForEntity(
                "/api/v1/twin/policy-evaluations",
                Map.of(
                        "from",
                        List.of(GATEWAY),
                        "operations",
                        List.of(Map.of("type", "REMOVE_ENTITY", "entity", GATEWAY))),
                String.class);

        assertThat(entityCount()).isEqualTo(entitiesBefore);
        assertThat(relationshipCount()).isEqualTo(relationshipsBefore);
    }

    @Test
    void impactRiskAndSimulationEndpointsStillWork() {
        freshUpAll();
        evaluate(proposal(List.of(GATEWAY), 6, null, List.of(removeEntity(INVENTORY_ITEMS))));

        ResponseEntity<ImpactResponse> impact =
                rest.getForEntity(
                        "/api/v1/twin/impact?from=" + INVENTORY_ITEMS, ImpactResponse.class);
        assertThat(impact.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(impact.getBody().impacted()).isNotEmpty();

        ResponseEntity<RiskResponse> risk =
                rest.getForEntity(
                        "/api/v1/twin/risk?from=" + INVENTORY_ITEMS, RiskResponse.class);
        assertThat(risk.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(risk.getBody().taxonomyVersion()).isEqualTo("risk-v1");

        ResponseEntity<String> simulation =
                rest.postForEntity(
                        "/api/v1/twin/simulations",
                        Map.of("from", List.of(GATEWAY), "operations", List.of()),
                        String.class);
        assertThat(simulation.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void errorBodiesLeakNothing() {
        for (ResponseEntity<String> response : List.of(
                rest.postForEntity(
                        "/api/v1/twin/policy-evaluations",
                        Map.of("operations", List.of()),
                        String.class),
                rest.postForEntity(
                        "/api/v1/twin/policy-evaluations",
                        Map.of(
                                "from",
                                List.of(GATEWAY),
                                "operations",
                                List.of(
                                        Map.of(
                                                "type", "REMOVE_ENTITY", "entity", GATEWAY))),
                        String.class))) {
            assertThat(response.getBody())
                    .doesNotContain("password", "stackTrace", "SQLException");
            assertThat(response.getBody()).doesNotContain("at com.soubhagya");
        }
    }
}
