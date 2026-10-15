package com.soubhagya.systivex.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.model.SystemRelationshipType;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure hypothetical-graph builder
 * ({@link HypotheticalGraph}): entity-removal cascades, explicit edge
 * removal, edge addition, summary counts, baseline immutability, and
 * determinism. No database, no Spring.
 */
class HypotheticalGraphTest {

    private static SystemEntity entity(String ref) {
        SystemEntity entity =
                new SystemEntity(SystemEntityType.SERVICE, ref, ref, "dev", Map.of());
        try {
            Field id = SystemEntity.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(entity, UUID.nameUUIDFromBytes(ref.getBytes(StandardCharsets.UTF_8)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    private static Map<UUID, SystemEntity> index(List<SystemEntity> entities) {
        Map<UUID, SystemEntity> index = new HashMap<>();
        for (SystemEntity entity : entities) {
            index.put(entity.getId(), entity);
        }
        return index;
    }

    private static SystemRelationship edge(
            SystemEntity source, SystemEntity target, SystemRelationshipType type) {
        return new SystemRelationship(source, target, type);
    }

    private static SimulationProposalValidator.ResolvedProposal proposal(
            List<UUID> removed, List<SimulationProposalValidator.ResolvedEdge> added,
            List<SimulationProposalValidator.ResolvedEdge> dropped) {
        return new SimulationProposalValidator.ResolvedProposal(removed, added, dropped);
    }

    private static SimulationProposalValidator.ResolvedEdge resolved(
            SystemEntity source, SystemEntity target, SystemRelationshipType type) {
        return new SimulationProposalValidator.ResolvedEdge(
                source.getId(), target.getId(), type);
    }

    @Test
    void entityRemovalCascadesToIncidentEdges() {
        SystemEntity gateway = entity("sim:gateway");
        SystemEntity order = entity("sim:order");
        SystemEntity inventory = entity("sim:inventory");
        List<SystemEntity> baseline = List.of(gateway, order, inventory);
        List<SystemRelationship> edges = new java.util.ArrayList<>(List.of(
                edge(gateway, order, SystemRelationshipType.CALLS),
                edge(order, inventory, SystemRelationshipType.CALLS)));

        HypotheticalGraph.Built built =
                HypotheticalGraph.build(
                        baseline,
                        edges,
                        proposal(
                                List.of(order.getId()), List.of(), List.of()),
                        index(baseline));

        assertThat(built.entities()).containsExactly(gateway, inventory);
        assertThat(built.edges()).isEmpty();
        assertThat(built.removedEntityCount()).isEqualTo(1);
        assertThat(built.addedRelationshipCount()).isEqualTo(0);
        // Both baseline edges touched the removed entity: cascade count 2.
        assertThat(built.removedRelationshipCount()).isEqualTo(2);
    }

    @Test
    void explicitEdgeRemovalKeepsBothEndpoints() {
        SystemEntity gateway = entity("sim:gateway");
        SystemEntity order = entity("sim:order");
        List<SystemEntity> baseline = List.of(gateway, order);
        List<SystemRelationship> edges = new java.util.ArrayList<>(List.of(
                edge(gateway, order, SystemRelationshipType.CALLS)));

        HypotheticalGraph.Built built =
                HypotheticalGraph.build(
                        baseline,
                        edges,
                        proposal(
                                List.of(),
                                List.of(),
                                List.of(
                                        resolved(
                                                gateway,
                                                order,
                                                SystemRelationshipType.CALLS))),
                        index(baseline));

        assertThat(built.entities()).containsExactly(gateway, order);
        assertThat(built.edges()).isEmpty();
        assertThat(built.removedEntityCount()).isEqualTo(0);
        assertThat(built.removedRelationshipCount()).isEqualTo(1);
    }

    @Test
    void edgeAdditionAppendsWithoutTouchingBaseline() {
        SystemEntity gateway = entity("sim:gateway");
        SystemEntity order = entity("sim:order");
        List<SystemEntity> baseline = List.of(gateway, order);
        List<SystemRelationship> edges =
                new java.util.ArrayList<>(List.of(
                        edge(gateway, order, SystemRelationshipType.CALLS)));

        HypotheticalGraph.Built built =
                HypotheticalGraph.build(
                        baseline,
                        edges,
                        proposal(
                                List.of(),
                                List.of(
                                        resolved(
                                                order,
                                                gateway,
                                                SystemRelationshipType.CALLS)),
                                List.of()),
                        index(baseline));

        assertThat(built.entities()).containsExactly(gateway, order);
        assertThat(built.edges()).hasSize(2);
        assertThat(built.addedRelationshipCount()).isEqualTo(1);
        assertThat(built.removedRelationshipCount()).isEqualTo(0);
        // Baseline collections are never mutated.
        assertThat(edges).hasSize(1);
        assertThat(baseline).hasSize(2);
    }

    @Test
    void emptyProposalLeavesWorkingSetsIdentical() {
        SystemEntity gateway = entity("sim:gateway");
        SystemEntity order = entity("sim:order");
        List<SystemEntity> baseline = List.of(gateway, order);
        List<SystemRelationship> edges = new java.util.ArrayList<>(List.of(
                edge(gateway, order, SystemRelationshipType.CALLS)));

        HypotheticalGraph.Built built =
                HypotheticalGraph.build(
                        baseline, edges, proposal(List.of(), List.of(), List.of()),
                        index(baseline));

        assertThat(built.entities()).containsExactlyElementsOf(baseline);
        assertThat(built.edges()).containsExactlyElementsOf(edges);
        assertThat(built.removedEntityCount()).isZero();
        assertThat(built.addedRelationshipCount()).isZero();
        assertThat(built.removedRelationshipCount()).isZero();
    }

    @Test
    void repeatedBuildIsDeterministic() {
        SystemEntity gateway = entity("sim:gateway");
        SystemEntity order = entity("sim:order");
        SystemEntity inventory = entity("sim:inventory");
        List<SystemEntity> baseline = List.of(gateway, order, inventory);
        List<SystemRelationship> edges = new java.util.ArrayList<>(List.of(
                edge(gateway, order, SystemRelationshipType.CALLS),
                edge(order, inventory, SystemRelationshipType.CALLS)));
        SimulationProposalValidator.ResolvedProposal change =
                proposal(
                        List.of(inventory.getId()),
                        List.of(resolved(order, gateway, SystemRelationshipType.CALLS)),
                        List.of());

        HypotheticalGraph.Built first =
                HypotheticalGraph.build(baseline, edges, change, index(baseline));
        HypotheticalGraph.Built second =
                HypotheticalGraph.build(baseline, edges, change, index(baseline));

        assertThat(first.entities()).isEqualTo(second.entities());
        assertThat(first.removedRelationshipCount())
                .isEqualTo(second.removedRelationshipCount());
        assertThat(first.addedRelationshipCount())
                .isEqualTo(second.addedRelationshipCount());
        assertThat(
                        first.edges().stream()
                                .map(e -> e.getSource().getId() + "|"
                                        + e.getTarget().getId() + "|"
                                        + e.getRelationshipType().name())
                                .toList())
                .isEqualTo(
                        second.edges().stream()
                                .map(e -> e.getSource().getId() + "|"
                                        + e.getTarget().getId() + "|"
                                        + e.getRelationshipType().name())
                                .toList());
    }
}
