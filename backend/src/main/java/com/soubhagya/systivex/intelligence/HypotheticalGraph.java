package com.soubhagya.systivex.intelligence;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure hypothetical-graph builder: applies a validated proposal to
 * in-memory working copies of the baseline snapshot.
 *
 * <p>{@code REMOVE_ENTITY} deletes the entity and every incident
 * relationship (cascade) from the hypothetical graph; explicit
 * {@code REMOVE_RELATIONSHIP} operations delete matching edges;
 * {@code ADD_RELATIONSHIP} operations append new edges. Nothing is
 * persisted, merged, or flushed — the baseline collections are never
 * mutated, and no repository, transaction, or I/O is involved.
 */
public final class HypotheticalGraph {

    private HypotheticalGraph() {}

    /** Hypothetical working sets plus the counts behind the summary. */
    public record Built(
            List<SystemEntity> entities,
            List<SystemRelationship> edges,
            int removedEntityCount,
            int addedRelationshipCount,
            int removedRelationshipCount) {}

    /**
     * Applies {@code proposal} (already validated) to copies of the
     * baseline snapshot.
     *
     * @param entityById baseline entities indexed by ID (added edges reuse
     *     these instances so traversal sees the same rows)
     */
    public static Built build(
            List<SystemEntity> baselineEntities,
            List<SystemRelationship> baselineEdges,
            SimulationProposalValidator.ResolvedProposal proposal,
            Map<UUID, SystemEntity> entityById) {
        List<SystemEntity> entities = new ArrayList<>(baselineEntities);
        List<SystemRelationship> edges = new ArrayList<>(baselineEdges);

        entities.removeIf(e -> proposal.removedEntityIds().contains(e.getId()));
        int afterEntityRemovals = edges.size();
        edges.removeIf(
                e -> proposal.removedEntityIds().contains(e.getSource().getId())
                        || proposal.removedEntityIds().contains(e.getTarget().getId()));
        int cascaded = afterEntityRemovals - edges.size();

        int explicitRemoved = 0;
        for (SimulationProposalValidator.ResolvedEdge doomed : proposal.removedEdges()) {
            int before = edges.size();
            UUID source = doomed.sourceId();
            UUID target = doomed.targetId();
            edges.removeIf(
                    e -> e.getSource().getId().equals(source)
                            && e.getTarget().getId().equals(target)
                            && e.getRelationshipType() == doomed.type());
            explicitRemoved += before - edges.size();
        }

        int added = 0;
        for (SimulationProposalValidator.ResolvedEdge fresh : proposal.addedEdges()) {
            SystemEntity source = entityById.get(fresh.sourceId());
            SystemEntity target = entityById.get(fresh.targetId());
            if (source == null || target == null) {
                // Unreachable after validation (endpoints resolved against
                // the baseline), guarded so a caller bug cannot NPE here.
                continue;
            }
            edges.add(new SystemRelationship(source, target, fresh.type()));
            added++;
        }

        return new Built(
                List.copyOf(entities),
                List.copyOf(edges),
                proposal.removedEntityIds().size(),
                added,
                explicitRemoved + cascaded);
    }
}
