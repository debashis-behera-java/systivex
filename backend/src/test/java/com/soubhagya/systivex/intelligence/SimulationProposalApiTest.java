package com.soubhagya.systivex.intelligence;

import static com.soubhagya.systivex.intelligence.SimulationApiSupport.GATEWAY;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.ORDER;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.addEdge;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.body;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.dropEdge;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.orderCallsInventoryDrop;
import static com.soubhagya.systivex.intelligence.SimulationApiSupport.removeEntity;
import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.observation.TwinSyncService;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * HTTP validation matrix for change simulation over the real Phase 4
 * graph: 400/404/409 semantics for roots, settings, operation shape,
 * identifier resolution, and every proposal-conflict class. Requires
 * Docker (Testcontainers PostgreSQL, like the other API suites).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SimulationProposalApiTest extends AbstractPostgresIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private TwinSyncService syncService;

    @BeforeEach
    void syncRealGraph() {
        Path realRoot = Path.of("").toAbsolutePath().resolve("target-services");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(realRoot), "real target-services tree not present");
        syncService.synchronize(realRoot);
    }

    private HttpStatusCode post(Map<String, Object> body) {
        return rest.postForEntity("/api/v1/twin/simulations", body, String.class)
                .getStatusCode();
    }

    @Test
    void missingOrBlankFromReturns400() {
        assertThat(post(body(null, null, null, List.of())))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(body(List.of(), null, null, List.of())))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(body(List.of("   "), null, null, List.of())))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tooManyFromReturns400() {
        assertThat(
                        post(
                                body(
                                        List.of(
                                                GATEWAY, GATEWAY, GATEWAY, GATEWAY, GATEWAY,
                                                GATEWAY, GATEWAY, GATEWAY, GATEWAY, GATEWAY,
                                                GATEWAY),
                                        null, null, List.of())))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void badDepthOrTypeReturns400() {
        assertThat(post(body(List.of(GATEWAY), 0, null, List.of())))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(body(List.of(GATEWAY), 11, null, List.of())))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(body(List.of(GATEWAY), null, List.of("SPACESHIP"), List.of())))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownFromReturns404() {
        assertThat(
                        post(
                                body(
                                        List.of("target-service:no-such-service"), null,
                                        null, List.of())))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void malformedOperationShapeReturns400() {
        assertThat(
                        post(
                                body(
                                        List.of(GATEWAY), null, null,
                                        List.of(Map.of("type", "REMOVE_ENTITY")))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        post(
                                body(
                                        List.of(GATEWAY), null, null,
                                        List.of(
                                                Map.of(
                                                        "type", "ADD_RELATIONSHIP",
                                                        "source", ORDER,
                                                        "target", GATEWAY)))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        post(
                                body(
                                        List.of(GATEWAY), null, null,
                                        List.of(Map.of("type", "TELEPORT")))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownOperationIdentifierReturns404() {
        assertThat(
                        post(
                                body(
                                        List.of(GATEWAY), null, null,
                                        List.of(removeEntity("target-service:nope")))))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removingRootReturns409() {
        assertThat(
                        post(
                                body(
                                        List.of(GATEWAY), null, null,
                                        List.of(removeEntity(GATEWAY)))))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void duplicateOperationsReturn409() {
        Map<String, Object> drop = orderCallsInventoryDrop();
        assertThat(post(body(List.of(GATEWAY), null, null, List.of(drop, drop))))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void contradictoryOperationsReturn409() {
        assertThat(
                        post(
                                body(
                                        List.of(GATEWAY), null, null,
                                        List.of(
                                                addEdge(
                                                        "target-service:order-service",
                                                        "target-service:gateway-service",
                                                        "CALLS"),
                                                dropEdge(
                                                        "target-service:order-service",
                                                        "target-service:gateway-service",
                                                        "CALLS")))))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void addingExistingEdgeReturns409() {
        assertThat(
                        post(
                                body(
                                        List.of(GATEWAY), null, null,
                                        List.of(
                                                addEdge(
                                                        "target-service:gateway-service",
                                                        ORDER, "CALLS")))))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void removingMissingEdgeReturns409() {
        assertThat(
                        post(
                                body(
                                        List.of(GATEWAY), null, null,
                                        List.of(
                                                dropEdge(
                                                        "target-service:gateway-service",
                                                        "target-service:payment-service",
                                                        "CALLS")))))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void edgeIncidentToRemovedEntityReturns409() {
        assertThat(
                        post(
                                body(
                                        List.of(GATEWAY), null, null,
                                        List.of(
                                                removeEntity(
                                                        "target-table:inventory_db"
                                                                + ".inventory_items"),
                                                addEdge(
                                                        "target-service:inventory-service",
                                                        "target-table:inventory_db"
                                                                + ".inventory_items",
                                                        "WRITES")))))
                .isEqualTo(HttpStatus.CONFLICT);
    }
}
