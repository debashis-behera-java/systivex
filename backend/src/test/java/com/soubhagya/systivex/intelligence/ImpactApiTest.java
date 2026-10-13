package com.soubhagya.systivex.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.observation.TwinSyncService;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import com.soubhagya.systivex.twin.api.CreateSystemEntityRequest;
import com.soubhagya.systivex.twin.api.CreateSystemRelationshipRequest;
import com.soubhagya.systivex.twin.api.SystemEntityResponse;
import com.soubhagya.systivex.twin.api.SystemRelationshipResponse;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.model.SystemRelationshipType;
import com.soubhagya.systivex.twin.repository.SystemRelationshipRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * HTTP surface of deterministic change-impact analysis: evidence paths over
 * the real Phase 4 graph (15 entities / 18 relationships), UUID and
 * external-reference inputs, 400/404 conventions, multi-start union, manual
 * cycles, read-only proof, stability, and leakage checks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ImpactApiTest extends AbstractPostgresIntegrationTest {

    private static final String INVENTORY_ITEMS = "target-table:inventory_db.inventory_items";
    private static final String PAYMENTS_TABLE = "target-table:payment_db.payments";

    @Autowired private TestRestTemplate rest;
    @Autowired private TwinSyncService syncService;
    @Autowired private SystemRelationshipRepository relationshipRepository;

    private Path realRoot;

    @BeforeEach
    void syncRealGraph() {
        realRoot = Path.of("").toAbsolutePath().resolve("target-services");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(realRoot), "real target-services tree not present");
        syncService.synchronize(realRoot);
    }

    private String impactUriTemplate(List<String> from, String extra) {
        // URI-template placeholders: TestRestTemplate encodes variables per
        // component, so external refs containing spaces, slashes, or colons
        // (e.g. "POST /api/v1/orders") survive the round trip. Never
        // pre-encode: the client would encode the '%' again.
        StringBuilder uri = new StringBuilder("/api/v1/twin/impact?");
        for (int i = 0; i < from.size(); i++) {
            if (i > 0) {
                uri.append('&');
            }
            uri.append("from={v").append(i).append('}');
        }
        if (extra != null && !extra.isBlank()) {
            uri.append('&').append(extra);
        }
        return uri.toString();
    }

    private static Map<String, String> impactVars(List<String> from) {
        Map<String, String> vars = new java.util.HashMap<>();
        for (int i = 0; i < from.size(); i++) {
            vars.put("v" + i, from.get(i));
        }
        return vars;
    }

    private ImpactResponse impact(String... from) {
        List<String> fromList = Arrays.asList(from);
        ResponseEntity<ImpactResponse> response =
                rest.getForEntity(
                        impactUriTemplate(fromList, null), ImpactResponse.class, impactVars(fromList));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private ResponseEntity<String> impactRaw(List<String> from, String extra) {
        return rest.getForEntity(
                impactUriTemplate(from, extra), String.class, impactVars(from));
    }

    private static ImpactResponse.ImpactedEntity impactedBy(
            ImpactResponse response, String externalRef) {
        return response.impacted().stream()
                .filter(e -> externalRef.equals(e.externalRef()))
                .findFirst()
                .orElse(null);
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

    @Test
    void phase4GraphPresent() {
        assertThat(entityCount()).isGreaterThanOrEqualTo(15);
        assertThat(relationshipCount()).isGreaterThanOrEqualTo(18);
    }

    @Test
    void inventoryTableImpactShowsEvidencePaths() {
        ImpactResponse response = impact(INVENTORY_ITEMS);

        assertThat(response.startedFrom()).hasSize(1);
        assertThat(response.startedFrom().get(0).externalRef()).isEqualTo(INVENTORY_ITEMS);
        assertThat(response.truncated()).isFalse();
        // The stored Phase 4 graph is a directed DAG (CALLS down the service
        // chain, DEPENDS_ON/CONTAINS/WRITES/EXPOSES fanning outward), so no
        // directed cycle exists to report — despite heavy bidirectional
        // backtracking and undirected-only loops (diamonds, service—table—
        // database triangles), which terminate silently by design.
        assertThat(response.cycleEncountered()).isFalse();
        assertThat(response.note()).contains("not confirmed breakage");

        // Reverse traversal from the table: writing service + parent database.
        ImpactResponse.ImpactedEntity writingService =
                impactedBy(response, "target-service:inventory-service");
        assertThat(writingService).isNotNull();
        assertThat(writingService.depth()).isEqualTo(1);
        assertThat(impactedBy(response, "target-database:inventory_db")).isNotNull();
        assertThat(impactedBy(response, "target-database:inventory_db").depth()).isEqualTo(1);
        // Upstream callers through reverse CALLS.
        assertThat(impactedBy(response, "target-service:order-service")).isNotNull();
        assertThat(impactedBy(response, "target-service:gateway-service")).isNotNull();
        // Sibling table in the same database, and the owning service's API.
        assertThat(impactedBy(response, "target-table:inventory_db.inventory_reservations"))
                .isNotNull();
        assertThat(
                        response.impacted().stream()
                                .anyMatch(
                                        e ->
                                                e.type() == SystemEntityType.API
                                                        && e.externalRef() != null
                                                        && e.externalRef()
                                                                .startsWith(
                                                                        "target-api:inventory-service:")))
                .isTrue();
        // Starts are never reported as impacted.
        assertThat(impactedBy(response, INVENTORY_ITEMS)).isNull();

        // Exact evidence path to order-service, verified against the stored
        // graph: inventory-service -WRITES-> inventory_items (traversed
        // backwards) then order-service -CALLS-> inventory-service (backwards).
        List<ImpactResponse.ImpactPath> orderPaths =
                response.paths().stream()
                        .filter(
                                p ->
                                        "target-service:order-service"
                                                .equals(p.targetExternalRef()))
                        .toList();
        assertThat(orderPaths).isNotEmpty();
        ImpactResponse.ImpactPath viaInventory =
                orderPaths.stream().filter(p -> p.depth() == 2).findFirst().orElse(null);
        assertThat(viaInventory).isNotNull();
        assertThat(viaInventory.steps()).hasSize(2);
        assertThat(viaInventory.steps().get(0).type())
                .isEqualTo(SystemRelationshipType.WRITES);
        assertThat(viaInventory.steps().get(0).direction()).isEqualTo(ImpactDirection.REVERSE);
        assertThat(viaInventory.steps().get(1).type()).isEqualTo(SystemRelationshipType.CALLS);
        assertThat(viaInventory.steps().get(1).direction()).isEqualTo(ImpactDirection.REVERSE);
        assertThat(viaInventory.explanation()).contains("may affect");

        // Every impacted entity carries at least one complete evidence path.
        for (ImpactResponse.ImpactedEntity impacted : response.impacted()) {
            assertThat(
                            response.paths().stream()
                                    .anyMatch(p -> p.targetId().equals(impacted.id())))
                    .as("evidence path for %s", impacted.externalRef())
                    .isTrue();
        }
    }

    @Test
    void uuidInputMatchesExternalRefInput() {
        ResponseEntity<SystemEntityResponse[]> listed =
                rest.getForEntity("/api/v1/twin/entities", SystemEntityResponse[].class);
        UUID tableId =
                Arrays.stream(listed.getBody())
                        .filter(e -> INVENTORY_ITEMS.equals(e.externalRef()))
                        .map(SystemEntityResponse::id)
                        .findFirst()
                        .orElseThrow();

        ImpactResponse byRef = impact(INVENTORY_ITEMS);
        ImpactResponse byId = impact(tableId.toString());

        assertThat(stable(byId)).isEqualTo(stable(byRef));
    }

    private static String stable(ImpactResponse response) {
        StringBuilder rendered = new StringBuilder();
        for (ImpactResponse.ImpactedEntity entity : response.impacted()) {
            rendered.append(entity.id()).append(entity.depth()).append(entity.viaStart());
        }
        for (ImpactResponse.ImpactPath path : response.paths()) {
            rendered.append(path.targetId()).append(path.explanation());
        }
        return rendered.toString();
    }

    @Test
    void multiStartUnionsResults() {
        ImpactResponse response = impact(INVENTORY_ITEMS, PAYMENTS_TABLE);

        assertThat(response.startedFrom()).hasSize(2);
        assertThat(impactedBy(response, "target-service:inventory-service")).isNotNull();
        assertThat(impactedBy(response, "target-service:payment-service")).isNotNull();
        assertThat(impactedBy(response, "target-service:inventory-service").viaStart())
                .isEqualTo(INVENTORY_ITEMS);
        assertThat(impactedBy(response, "target-service:payment-service").viaStart())
                .isEqualTo(PAYMENTS_TABLE);
    }

    @Test
    void missingFromReturns400() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/twin/impact", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankFromReturns400() {
        ResponseEntity<String> response = impactRaw(List.of("   "), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tooManyFromReturns400WithoutTraversal() {
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add("target-service:order-service");
        }
        ResponseEntity<String> response = impactRaw(eleven, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void maxDepthOutOfRangeReturns400() {
        ResponseEntity<String> zero = impactRaw(List.of(INVENTORY_ITEMS), "maxDepth=0");
        assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> eleven = impactRaw(List.of(INVENTORY_ITEMS), "maxDepth=11");
        assertThat(eleven.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void malformedMaxDepthReturns400() {
        ResponseEntity<String> response = impactRaw(List.of(INVENTORY_ITEMS), "maxDepth=deep");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownIncludeTypeReturns400() {
        ResponseEntity<String> response =
                impactRaw(List.of(INVENTORY_ITEMS), "includeTypes=SPACESHIP");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownExternalRefReturns404() {
        ResponseEntity<String> response =
                impactRaw(List.of("target-service:no-such-service"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownUuidReturns404() {
        ResponseEntity<String> response =
                impactRaw(List.of(UUID.randomUUID().toString()), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void includeTypesFiltersOutput() {
        List<String> from = List.of(INVENTORY_ITEMS);
        ResponseEntity<ImpactResponse> response =
                rest.getForEntity(
                        impactUriTemplate(from, "includeTypes=SERVICE"),
                        ImpactResponse.class,
                        impactVars(from));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().impacted())
                .allMatch(e -> e.type() == SystemEntityType.SERVICE);
        // Traversal still passes through filtered databases/tables.
        assertThat(impactedBy(response.getBody(), "target-service:order-service")).isNotNull();
    }

    @Test
    void maxDepthOneReturnsDirectNeighboursOnly() {
        List<String> from = List.of(INVENTORY_ITEMS);
        ResponseEntity<ImpactResponse> response =
                rest.getForEntity(
                        impactUriTemplate(from, "maxDepth=1"),
                        ImpactResponse.class,
                        impactVars(from));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().impacted())
                .allMatch(e -> e.depth() == 1);
        assertThat(impactedBy(response.getBody(), "target-service:inventory-service"))
                .isNotNull();
        assertThat(impactedBy(response.getBody(), "target-service:order-service")).isNull();
    }

    @Test
    void manualCycleTerminatesAndIsReported() {
        SystemEntityResponse a = createManualEntity("cycle-a", "impact-test:cycle-a");
        SystemEntityResponse b = createManualEntity("cycle-b", "impact-test:cycle-b");
        createManualEdge(a.id(), b.id(), SystemRelationshipType.CALLS);
        createManualEdge(b.id(), a.id(), SystemRelationshipType.CALLS);

        ImpactResponse response = impact("impact-test:cycle-a");

        assertThat(response.cycleEncountered()).isTrue();
        assertThat(impactedBy(response, "impact-test:cycle-b")).isNotNull();
    }

    @Test
    void queriesDoNotMutateGraph() {
        int entitiesBefore = entityCount();
        int relationshipsBefore = relationshipCount();

        impact(INVENTORY_ITEMS);
        impact(INVENTORY_ITEMS, PAYMENTS_TABLE);
        impactRaw(List.of("target-service:missing-xyz"), null);

        assertThat(entityCount()).isEqualTo(entitiesBefore);
        assertThat(relationshipCount()).isEqualTo(relationshipsBefore);
    }

    @Test
    void repeatQueryIsStable() {
        ImpactResponse first = impact(INVENTORY_ITEMS);
        ImpactResponse second = impact(INVENTORY_ITEMS);
        assertThat(stable(second)).isEqualTo(stable(first));
    }

    @Test
    void errorBodiesLeakNothing() {
        ResponseEntity<String> missing =
                rest.getForEntity("/api/v1/twin/impact", String.class);
        ResponseEntity<String> unknown = impactRaw(List.of("target-service:missing-xyz"), null);
        for (ResponseEntity<String> response : List.of(missing, unknown)) {
            assertThat(response.getBody()).doesNotContain("password", "stackTrace", "SQLException");
            assertThat(response.getBody()).doesNotContain("at com.soubhagya");
        }
        ImpactResponse ok = impact(INVENTORY_ITEMS);
        assertThat(ok.toString()).doesNotContain("password");
    }

    @Test
    void relationshipFindersReturnAdjacentEdges() {
        ResponseEntity<SystemEntityResponse[]> listed =
                rest.getForEntity("/api/v1/twin/entities", SystemEntityResponse[].class);
        SystemEntityResponse order =
                Arrays.stream(listed.getBody())
                        .filter(e -> "target-service:order-service".equals(e.externalRef()))
                        .findFirst()
                        .orElseThrow();

        // order-service CALLS inventory + payment, DEPENDS_ON order_db,
        // WRITES orders, EXPOSES its API: outgoing edges only, all fetched.
        assertThat(relationshipRepository.findBySourceId(order.id())).hasSize(5);
        // gateway-service CALLS order-service: one incoming edge.
        assertThat(relationshipRepository.findByTargetId(order.id())).hasSize(1);
        assertThat(relationshipRepository.findByTargetId(order.id()).get(0).getSource())
                .isNotNull();
    }

    private SystemEntityResponse createManualEntity(String name, String externalRef) {
        // Unique per test class run; the shared container DB may already hold
        // rows from other classes, so never assume global counts here.
        ResponseEntity<SystemEntityResponse[]> existing =
                rest.getForEntity("/api/v1/twin/entities", SystemEntityResponse[].class);
        if (existing.getBody() != null) {
            for (SystemEntityResponse entity : existing.getBody()) {
                if (externalRef.equals(entity.externalRef())) {
                    return entity;
                }
            }
        }
        ResponseEntity<SystemEntityResponse> created =
                rest.postForEntity(
                        "/api/v1/twin/entities",
                        new CreateSystemEntityRequest(
                                SystemEntityType.SERVICE, name, externalRef, "dev", Map.of()),
                        SystemEntityResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody();
    }

    private void createManualEdge(UUID source, UUID target, SystemRelationshipType type) {
        List<SystemRelationshipResponse> current =
                new ArrayList<>(
                        Arrays.asList(
                                rest.getForEntity(
                                                "/api/v1/twin/relationships",
                                                SystemRelationshipResponse[].class)
                                        .getBody()));
        boolean exists =
                current.stream()
                        .anyMatch(
                                rel ->
                                        rel.sourceId().equals(source)
                                                && rel.targetId().equals(target));
        if (exists) {
            return;
        }
        ResponseEntity<SystemRelationshipResponse> created =
                rest.postForEntity(
                        "/api/v1/twin/relationships",
                        new CreateSystemRelationshipRequest(source, target, type),
                        SystemRelationshipResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
