package com.soubhagya.systivex.intelligence;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure deterministic BFS core for change-impact analysis (extracted from
 * {@link ImpactAnalysisService}).
 *
 * <p>No repository access, no Spring: callers supply the entity/edge
 * snapshot plus validated inputs, so unit tests exercise every direction,
 * diamond, cycle, and cap without a database. Stored directions are
 * respected; every step records the direction actually taken. Revisits on
 * the current path always terminate the walk and are reported only for
 * genuine directed cycles. Starts, adjacency, and output are sorted.
 */
final class ImpactTraversal {

    static final int MAX_NODES = 500;
    static final int MAX_PATHS = 2000;

    static final String NOTE =
            "Potentially affected components derived from stored twin relationships;"
                    + " downstream dependencies are structural reachability, not confirmed breakage."
                    + " Every result carries at least one complete evidence path; verify against"
                    + " the twin and live backend state before acting on it.";

    private ImpactTraversal() {}

    /** Production limits: query bounds from the validator, traversal caps local. */
    static ImpactResponse.Limits defaultLimits() {
        return new ImpactResponse.Limits(
                ImpactQueryValidator.MAX_STARTS,
                ImpactQueryValidator.MAX_DEPTH_ALLOWED,
                MAX_NODES,
                MAX_PATHS);
    }

    /**
     * Runs the deterministic BFS core over caller-supplied entity and edge
     * collections. Callers must have validated and resolved inputs first.
     */
    static ImpactResponse analyzeGraph(
            List<SystemEntity> starts,
            List<SystemEntity> allEntities,
            List<SystemRelationship> allEdges,
            int maxDepth,
            Set<SystemEntityType> includeTypes,
            ImpactResponse.Limits limits) {
        Map<UUID, SystemEntity> index = new HashMap<>();
        for (SystemEntity entity : allEntities) {
            index.put(entity.getId(), entity);
        }
        // Resolved starts are authoritative even if a stale snapshot missed them.
        for (SystemEntity start : starts) {
            index.putIfAbsent(start.getId(), start);
        }

        Map<UUID, List<SystemRelationship>> outgoing = new HashMap<>();
        Map<UUID, List<SystemRelationship>> incoming = new HashMap<>();
        for (SystemRelationship edge : allEdges) {
            UUID sourceId = edge.getSource().getId();
            UUID targetId = edge.getTarget().getId();
            // Defensive: ignore edges dangling outside the snapshot.
            if (!index.containsKey(sourceId) || !index.containsKey(targetId)) {
                continue;
            }
            outgoing.computeIfAbsent(sourceId, k -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(targetId, k -> new ArrayList<>()).add(edge);
        }
        Comparator<SystemRelationship> edgeOrder =
                Comparator.comparing(
                                (SystemRelationship e) ->
                                        otherRef(e, index),
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(e -> e.getRelationshipType().name())
                        .thenComparing(
                                e ->
                                        otherId(e).toString());
        for (List<SystemRelationship> list : outgoing.values()) {
            list.sort(edgeOrder);
        }
        for (List<SystemRelationship> list : incoming.values()) {
            list.sort(edgeOrder);
        }

        Set<UUID> startIds = new HashSet<>();
        Map<UUID, String> startRefById = new HashMap<>();
        for (SystemEntity start : starts) {
            startIds.add(start.getId());
            startRefById.put(start.getId(), startKey(start));
        }

        Map<UUID, Integer> minDepth = new HashMap<>();
        Map<UUID, String> viaStart = new HashMap<>();
        Set<UUID> discovered = new HashSet<>(startIds);
        List<RecordedPath> recorded = new ArrayList<>();
        boolean cycleEncountered = false;
        boolean truncated = false;
        String truncationReason = null;

        Deque<PathState> queue = new ArrayDeque<>();
        for (SystemEntity start : starts) {
            minDepth.put(start.getId(), 0);
            viaStart.put(start.getId(), startKey(start));
            queue.addLast(new PathState(start.getId(), startKey(start), new ArrayList<>(), null, null));
        }

        while (!queue.isEmpty()) {
            PathState current = queue.pollFirst();
            int depth = current.steps().size();
            if (depth >= maxDepth) {
                continue;
            }
            List<Expansion> expansions = new ArrayList<>();
            for (SystemRelationship edge :
                    outgoing.getOrDefault(current.nodeId(), List.of())) {
                expansions.add(
                        new Expansion(
                                edge, edge.getTarget().getId(), ImpactDirection.FORWARD));
            }
            for (SystemRelationship edge :
                    incoming.getOrDefault(current.nodeId(), List.of())) {
                expansions.add(
                        new Expansion(
                                edge, edge.getSource().getId(), ImpactDirection.REVERSE));
            }
            // Deterministic across directions: forward steps before reverse
            // steps only as a tie-break after the per-list ordering above is
            // merged by neighbour identity.
            expansions.sort(
                    Comparator.comparing(
                                    (Expansion e) -> neighbourRef(e, index),
                                    Comparator.nullsLast(String::compareTo))
                            .thenComparing(e -> e.edge().getRelationshipType().name())
                            .thenComparing(e -> e.direction().name())
                            .thenComparing(e -> e.neighbourId().toString()));

            for (Expansion expansion : expansions) {
                UUID neighbour = expansion.neighbourId();
                if (current.isBacktrack(expansion)) {
                    // Ordinary bidirectional backtracking (A -> B -> A over
                    // the same stored relationship) is not a graph cycle: the
                    // target is on the path by construction, so no simple
                    // path is lost by skipping it silently.
                    continue;
                }
                if (current.contains(neighbour)) {
                    // A revisit through a different stored relationship closes
                    // a loop. Report it only when the loop is a genuine
                    // directed cycle — every step (path plus closing edge)
                    // follows one consistent traversal direction. Mixed-
                    // direction (undirected-only) loops such as diamonds or
                    // service—table—database triangles are cut silently like
                    // backtracks: all simple paths are already recorded.
                    if (isDirectedLoop(current.steps(), expansion.direction())) {
                        cycleEncountered = true;
                    }
                    continue;
                }
                int nextDepth = depth + 1;
                List<ImpactExplanation.StepView> nextSteps = new ArrayList<>(current.steps());
                nextSteps.add(
                        ImpactExplanation.StepView.of(
                                expansion.edge(), expansion.direction(), index));
                if (startIds.contains(neighbour)) {
                    // Paths back to a start node are not impact evidence;
                    // the start itself stays listed in startedFrom.
                    continue;
                }
                boolean isNewNode = discovered.add(neighbour);
                if (isNewNode && discovered.size() > limits.maxNodes()) {
                    discovered.remove(neighbour);
                    truncated = true;
                    truncationReason = "node-cap";
                    break;
                }
                boolean betterDepth =
                        !minDepth.containsKey(neighbour)
                                || nextDepth < minDepth.get(neighbour);
                boolean tieBetterStart =
                        !betterDepth
                                && nextDepth == minDepth.getOrDefault(neighbour, nextDepth)
                                && current.startRef().compareTo(
                                                viaStart.getOrDefault(neighbour, current.startRef()))
                                        < 0;
                if (betterDepth || tieBetterStart) {
                    minDepth.put(neighbour, nextDepth);
                    viaStart.put(neighbour, current.startRef());
                }
                if (recorded.size() >= limits.maxPaths()) {
                    truncated = true;
                    truncationReason = "path-cap";
                    break;
                }
                recorded.add(new RecordedPath(neighbour, nextDepth, current.startRef(), nextSteps));
                queue.addLast(
                        new PathState(
                                neighbour,
                                current.startRef(),
                                nextSteps,
                                expansion.edge(),
                                expansion.direction()));
            }
            if (truncated) {
                break;
            }
        }

        List<ImpactResponse.ImpactedEntity> impacted = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : minDepth.entrySet()) {
            UUID id = entry.getKey();
            if (startIds.contains(id)) {
                continue;
            }
            SystemEntity entity = index.get(id);
            if (entity == null || !includeTypes.contains(entity.getType())) {
                continue;
            }
            impacted.add(
                    new ImpactResponse.ImpactedEntity(
                            id,
                            entity.getType(),
                            entity.getName(),
                            entity.getExternalRef(),
                            entry.getValue(),
                            viaStart.get(id)));
        }
        impacted.sort(
                Comparator.comparingInt(ImpactResponse.ImpactedEntity::depth)
                        .thenComparing(e -> e.type().name())
                        .thenComparing(
                                ImpactResponse.ImpactedEntity::externalRef,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(e -> e.id().toString()));

        Set<UUID> reportedIds = new HashSet<>();
        for (ImpactResponse.ImpactedEntity entity : impacted) {
            reportedIds.add(entity.id());
        }
        List<ImpactResponse.ImpactPath> paths = new ArrayList<>();
        for (RecordedPath path : recorded) {
            if (!reportedIds.contains(path.targetId())) {
                continue;
            }
            List<ImpactResponse.ImpactStep> steps = new ArrayList<>();
            for (ImpactExplanation.StepView step : path.steps()) {
                steps.add(
                        new ImpactResponse.ImpactStep(
                                step.sourceId(),
                                step.sourceRef(),
                                step.targetId(),
                                step.targetRef(),
                                step.type(),
                                step.direction()));
            }
            paths.add(
                    new ImpactResponse.ImpactPath(
                            path.targetId(),
                            ImpactExplanation.displayRef(index.get(path.targetId())),
                            path.depth(),
                            List.copyOf(steps),
                            ImpactExplanation.explain(path.steps())));
        }
        paths.sort(
                Comparator.comparing(
                                ImpactResponse.ImpactPath::targetExternalRef,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparingInt(ImpactResponse.ImpactPath::depth)
                        .thenComparing(
                                p -> ImpactExplanation.renderKey(p.steps()))
                        .thenComparing(p -> p.targetId().toString()));

        List<ImpactResponse.StartEntity> startedFrom = new ArrayList<>();
        for (SystemEntity start : starts) {
            startedFrom.add(
                    new ImpactResponse.StartEntity(
                            start.getId(),
                            start.getType(),
                            start.getName(),
                            start.getExternalRef(),
                            start.getEnvironment()));
        }

        return new ImpactResponse(
                List.copyOf(startedFrom),
                List.copyOf(impacted),
                List.copyOf(paths),
                maxDepth,
                discovered.size(),
                paths.size(),
                truncated,
                truncationReason,
                cycleEncountered,
                Instant.now(),
                index.size(),
                allEdges.size(),
                limits,
                NOTE);
    }

    private static String startKey(SystemEntity entity) {
        return entity.getExternalRef() != null ? entity.getExternalRef() : entity.getId().toString();
    }

    private static UUID otherId(SystemRelationship edge) {
        // Tie-break context depends on the adjacency list being sorted; use a
        // stable composite so outgoing and incoming sorts stay deterministic.
        return edge.getTarget().getId();
    }

    private static String otherRef(
            SystemRelationship edge, Map<UUID, SystemEntity> index) {
        SystemEntity target = index.get(edge.getTarget().getId());
        String ref = target == null ? null : target.getExternalRef();
        if (ref == null) {
            SystemEntity source = index.get(edge.getSource().getId());
            ref = source == null ? null : source.getExternalRef();
        }
        return ref;
    }

    private static String neighbourRef(Expansion expansion, Map<UUID, SystemEntity> index) {
        SystemEntity entity = index.get(expansion.neighbourId());
        return entity == null ? null : entity.getExternalRef();
    }

    private record Expansion(
            SystemRelationship edge, UUID neighbourId, ImpactDirection direction) {}

    private record PathState(
            UUID nodeId,
            String startRef,
            List<ImpactExplanation.StepView> steps,
            SystemRelationship arrivedViaEdge,
            ImpactDirection arrivedViaDirection) {
        boolean contains(UUID id) {
            if (nodeId.equals(id)) {
                return true;
            }
            for (ImpactExplanation.StepView step : steps) {
                if (step.sourceId().equals(id) || step.targetId().equals(id)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * True when the expansion walks straight back over the stored
         * relationship the walk arrived on. Identity comparison is exact
         * here: the outgoing/incoming adjacency maps hold the same edge
         * instances from the single read-only snapshot.
         */
        boolean isBacktrack(Expansion expansion) {
            return arrivedViaEdge != null
                    && arrivedViaEdge == expansion.edge()
                    && arrivedViaDirection != expansion.direction();
        }
    }

    /**
     * True when closing the current path with an edge of the given traversal
     * direction forms a directed cycle: every step follows the same
     * direction, so the loop is consistent in stored orientation (followed
     * either forwards or backwards). An empty path (self-loop on the current
     * node) counts as a directed cycle.
     */
    static boolean isDirectedLoop(
            List<ImpactExplanation.StepView> steps, ImpactDirection closingDirection) {
        for (ImpactExplanation.StepView step : steps) {
            if (step.direction() != closingDirection) {
                return false;
            }
        }
        return true;
    }

    private record RecordedPath(
            UUID targetId, int depth, String startRef, List<ImpactExplanation.StepView> steps) {}
}
