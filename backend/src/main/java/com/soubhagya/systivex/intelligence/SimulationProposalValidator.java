package com.soubhagya.systivex.intelligence;

import com.soubhagya.systivex.twin.model.SystemRelationshipType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure change-proposal validator: no repository access, no Spring, no I/O.
 *
 * <p>Validation order is fixed and deterministic (first violation wins
 * within each phase, phases run in the order below):
 *
 * <ol>
 *   <li>Operation shape — 400 ({@link IllegalArgumentException}): null
 *       operation or type, blank {@code entity} for {@code REMOVE_ENTITY},
 *       blank {@code source}/{@code target} or null
 *       {@code relationshipType} for relationship operations.</li>
 *   <li>Identifier resolution — 404: every referenced identifier is resolved
 *       through the caller-supplied resolver (impact-query semantics:
 *       UUID first, then external reference, 404 on unknown).</li>
 *   <li>Proposal conflicts — 409 ({@link ProposalConflictException}),
 *       checked in this order: removing an analysis root; duplicate exact
 *       operations (compared canonically, so a UUID and its external
 *       reference count as the same endpoint); contradictory
 *       add-plus-remove of the same edge; adding an already-existing edge
 *       or removing a non-existent one; any relationship operation
 *       incident to an entity removed by the same proposal.</li>
 * </ol>
 */
public final class SimulationProposalValidator {

    private SimulationProposalValidator() {}

    /** Resolves one UUID-or-external-reference identifier; 404 on unknown. */
    @FunctionalInterface
    public interface EntityResolver {
        UUID resolve(String raw);
    }

    /** One relationship operation with identifiers resolved to entity UUIDs. */
    public record ResolvedEdge(UUID sourceId, UUID targetId, SystemRelationshipType type) {
        String key() {
            return sourceId + "|" + targetId + "|" + type.name();
        }
    }

    /** A fully validated proposal: canonical IDs, deterministic order. */
    public record ResolvedProposal(
            List<UUID> removedEntityIds,
            List<ResolvedEdge> addedEdges,
            List<ResolvedEdge> removedEdges) {}

    /**
     * Validates {@code operations} against the analysis roots and the
     * baseline edge set. A null list is the null proposal (no changes).
     *
     * @param rootIds analysis-root entity IDs (removing one is a conflict)
     * @param existingEdgeKeys baseline {@code source|target|TYPE} edge keys
     * @param resolver identifier resolution with 404 semantics
     */
    public static ResolvedProposal validate(
            List<SimulationOperation> operations,
            Set<UUID> rootIds,
            Set<String> existingEdgeKeys,
            EntityResolver resolver) {
        List<SimulationOperation> ops = operations == null ? List.of() : operations;
        for (SimulationOperation op : ops) {
            requireShape(op);
        }

        List<UUID> removedIds = new ArrayList<>();
        List<ResolvedEdge> added = new ArrayList<>();
        List<ResolvedEdge> removed = new ArrayList<>();
        List<String> canonicalOpKeys = new ArrayList<>();
        for (SimulationOperation op : ops) {
            if (op.type() == SimulationOperationType.REMOVE_ENTITY) {
                UUID id = resolver.resolve(op.entity().trim());
                removedIds.add(id);
                canonicalOpKeys.add("REMOVE_ENTITY|" + id);
            } else {
                UUID source = resolver.resolve(op.source().trim());
                UUID target = resolver.resolve(op.target().trim());
                ResolvedEdge edge = new ResolvedEdge(source, target, op.relationshipType());
                if (op.type() == SimulationOperationType.ADD_RELATIONSHIP) {
                    added.add(edge);
                } else {
                    removed.add(edge);
                }
                canonicalOpKeys.add(op.type().name() + "|" + edge.key());
            }
        }

        Set<UUID> removedSet = new LinkedHashSet<>(removedIds);
        for (UUID id : removedSet) {
            if (rootIds.contains(id)) {
                throw new ProposalConflictException(
                        "Cannot remove an analysis root: " + id);
            }
        }

        Set<String> seenOps = new LinkedHashSet<>();
        for (int i = 0; i < ops.size(); i++) {
            if (!seenOps.add(canonicalOpKeys.get(i))) {
                throw new ProposalConflictException(
                        "Duplicate operation: " + describe(ops.get(i)));
            }
        }

        // Contradictory pairs: the same canonical edge both added and removed.
        // (Exact duplicates are already rejected above, so set construction
        // below only feeds the contradiction check.)
        Set<String> addedKeys = new LinkedHashSet<>();
        for (ResolvedEdge edge : added) {
            addedKeys.add(edge.key());
        }
        Set<String> removedKeys = new LinkedHashSet<>();
        for (ResolvedEdge edge : removed) {
            removedKeys.add(edge.key());
        }
        for (String key : addedKeys) {
            if (removedKeys.contains(key)) {
                throw new ProposalConflictException(
                        "Contradictory operations for edge: " + key);
            }
        }

        for (ResolvedEdge edge : added) {
            if (existingEdgeKeys.contains(edge.key())) {
                throw new ProposalConflictException("Edge already exists: " + edge.key());
            }
        }
        for (ResolvedEdge edge : removed) {
            if (!existingEdgeKeys.contains(edge.key())) {
                throw new ProposalConflictException(
                        "Relationship does not exist: " + edge.key());
            }
        }

        for (ResolvedEdge edge : added) {
            requireIncident(edge, removedSet, "added");
        }
        for (ResolvedEdge edge : removed) {
            requireIncident(edge, removedSet, "removed");
        }

        return new ResolvedProposal(
                List.copyOf(new LinkedHashSet<>(removedIds)),
                List.copyOf(added),
                List.copyOf(removed));
    }

    private static void requireShape(SimulationOperation op) {
        if (op == null || op.type() == null) {
            throw new IllegalArgumentException("Operation type is required");
        }
        switch (op.type()) {
            case REMOVE_ENTITY -> {
                if (op.entity() == null || op.entity().isBlank()) {
                    throw new IllegalArgumentException(
                            "entity is required for REMOVE_ENTITY");
                }
            }
            case ADD_RELATIONSHIP, REMOVE_RELATIONSHIP -> {
                if (op.source() == null
                        || op.source().isBlank()
                        || op.target() == null
                        || op.target().isBlank()) {
                    throw new IllegalArgumentException(
                            "source and target are required for " + op.type());
                }
                if (op.relationshipType() == null) {
                    throw new IllegalArgumentException(
                            "relationshipType is required for " + op.type());
                }
            }
        }
    }

    private static void requireIncident(
            ResolvedEdge edge, Set<UUID> removedSet, String verb) {
        if (removedSet.contains(edge.sourceId()) || removedSet.contains(edge.targetId())) {
            throw new ProposalConflictException(
                    "Cannot " + verb + " relationship incident to an entity removed by"
                            + " this proposal: " + edge.key());
        }
    }

    private static String describe(SimulationOperation op) {
        return switch (op.type()) {
            case REMOVE_ENTITY -> "REMOVE_ENTITY " + op.entity().trim();
            case ADD_RELATIONSHIP -> "ADD_RELATIONSHIP " + op.source().trim() + " "
                    + op.relationshipType().name() + " " + op.target().trim();
            case REMOVE_RELATIONSHIP -> "REMOVE_RELATIONSHIP " + op.source().trim() + " "
                    + op.relationshipType().name() + " " + op.target().trim();
        };
    }
}
