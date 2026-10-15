package com.soubhagya.systivex.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.twin.api.SystemEntityResponse;
import com.soubhagya.systivex.twin.api.SystemRelationshipResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Shared request builders and stable renderers for the simulation API suites. */
final class SimulationApiSupport {

    static final String GATEWAY = "target-service:gateway-service";
    static final String ORDER = "target-service:order-service";
    static final String INVENTORY_ITEMS = "target-table:inventory_db.inventory_items";

    private SimulationApiSupport() {}

    static Map<String, Object> body(
            Object from, Object maxDepth, Object includeTypes, Object operations) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (from != null) {
            body.put("from", from);
        }
        if (maxDepth != null) {
            body.put("maxDepth", maxDepth);
        }
        if (includeTypes != null) {
            body.put("includeTypes", includeTypes);
        }
        if (operations != null) {
            body.put("operations", operations);
        }
        return body;
    }

    static Map<String, Object> removeEntity(String entity) {
        return Map.of("type", "REMOVE_ENTITY", "entity", entity);
    }

    static Map<String, Object> addEdge(String source, String target, String type) {
        return Map.of(
                "type", "ADD_RELATIONSHIP",
                "source", source, "target", target, "relationshipType", type);
    }

    static Map<String, Object> dropEdge(String source, String target, String type) {
        return Map.of(
                "type", "REMOVE_RELATIONSHIP",
                "source", source, "target", target, "relationshipType", type);
    }

    static Map<String, Object> orderCallsInventoryDrop() {
        return dropEdge(
                "target-service:order-service", "target-service:inventory-service", "CALLS");
    }

    static int entityCount(TestRestTemplate rest) {
        ResponseEntity<SystemEntityResponse[]> response =
                rest.getForEntity("/api/v1/twin/entities", SystemEntityResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody() == null ? 0 : response.getBody().length;
    }

    static int relationshipCount(TestRestTemplate rest) {
        ResponseEntity<SystemRelationshipResponse[]> response =
                rest.getForEntity(
                        "/api/v1/twin/relationships", SystemRelationshipResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody() == null ? 0 : response.getBody().length;
    }

    static String stable(SimulationResponse response) {
        StringBuilder rendered = new StringBuilder();
        rendered.append(response.simulated()).append(response.advisory());
        rendered.append(response.settings());
        for (ImpactResponse.ImpactedEntity entity : response.before().impact().impacted()) {
            rendered.append(entity.id()).append(entity.depth()).append(entity.viaStart());
        }
        for (ImpactResponse.ImpactedEntity entity : response.after().impact().impacted()) {
            rendered.append(entity.id()).append(entity.depth()).append(entity.viaStart());
        }
        rendered.append(response.delta());
        rendered.append(response.graph());
        return rendered.toString();
    }
}
