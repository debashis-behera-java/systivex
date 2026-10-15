package com.soubhagya.systivex.intelligence;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import com.soubhagya.systivex.twin.repository.SystemRelationshipRepository;
import com.soubhagya.systivex.twin.service.EntityNotFoundException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 5 deterministic change-impact analysis: read-only BFS over the
 * persisted twin graph.
 *
 * <p>Stored directions respected (see {@code TargetRepositoryScanner}):
 * {@code SERVICE -CALLS-> SERVICE}, {@code SERVICE -DEPENDS_ON-> DATABASE},
 * {@code DATABASE -CONTAINS-> TABLE}, {@code SERVICE -WRITES-> TABLE},
 * {@code SERVICE -EXPOSES-> API}. Both forward and reverse traversal are
 * supported, and every reported step records the direction actually taken,
 * so a downstream dependency is never silently presented as confirmed
 * breakage. Relationship types with no documented domain meaning (including
 * manually created ones) are traversed as structural reachability and labelled
 * as such — never given invented semantics.
 *
 * <p>Determinism: start set, adjacency, and output are all sorted; repeated
 * runs over an unchanged graph return identically ordered results. Genuine
 * directed cycles terminate via a per-path revisit guard and set
 * {@code cycleEncountered}: an expansion that would revisit a node already
 * on the current path is always cut (so traversal terminates), and it is
 * reported only when the closed loop follows one consistent traversal
 * direction — a directed cycle in stored orientation, walked forwards or
 * backwards. This covers multi-vertex directed cycles, directed 2-cycles
 * over distinct relationships, self-loops, and returns to a start via
 * another edge. Ordinary bidirectional backtracking (A -> B -> A over the
 * same stored relationship) and mixed-direction undirected-only loops
 * (diamonds, service—table—database triangles) are cut silently: they are
 * traversal mechanics, not graph cycles, and every simple path is recorded
 * regardless.
 * Alternative paths to an already-reached node are still recorded (up to the
 * path cap) — a node visited through one route does not suppress evidence
 * arriving through another.
 *
 * <p>Read-only: one {@code readOnly} transaction, no writes, no migration,
 * no filesystem or network access. Oversized input fails before any graph
 * work (T2: validated, size-bounded, fail-closed).
 */
@Service
public class ImpactAnalysisService {

    static final int MAX_STARTS = 10;
    static final int DEFAULT_MAX_DEPTH = 6;
    static final int MAX_DEPTH_ALLOWED = 10;
    static final int MAX_NODES = 500;
    static final int MAX_PATHS = 2000;

    static final Set<SystemEntityType> DEFAULT_INCLUDE_TYPES =
            EnumSet.of(
                    SystemEntityType.SERVICE,
                    SystemEntityType.API,
                    SystemEntityType.DATABASE,
                    SystemEntityType.TABLE);

    static final String NOTE =
            "Potentially affected components derived from stored twin relationships;"
                    + " downstream dependencies are structural reachability, not confirmed breakage."
                    + " Every result carries at least one complete evidence path; verify against"
                    + " the twin and live backend state before acting on it.";

    private final SystemEntityRepository entities;
    private final SystemRelationshipRepository relationships;

    public ImpactAnalysisService(
            SystemEntityRepository entities, SystemRelationshipRepository relationships) {
        this.entities = entities;
        this.relationships = relationships;
    }

    /**
     * Validates raw query input, resolves every starting entity, and runs the
     * analysis in one read-only transaction. Blank/oversized input fails with
     * {@link IllegalArgumentException} (400); unknown identifiers fail with
     * {@link EntityNotFoundException} (404).
     */
    @Transactional(readOnly = true)
    public ImpactResponse analyze(
            List<String> rawFrom, Integer rawMaxDepth, List<String> rawIncludeTypes) {
        ParsedInputs inputs = parseInputs(rawFrom, rawMaxDepth, rawIncludeTypes);
        List<SystemEntity> allEntities = entities.findAll();
        List<SystemRelationship> allRelationships = relationships.findAll();
        return analyzeResolved(
                inputs.starts(), inputs.maxDepth(), inputs.includeTypes(),
                allEntities, allRelationships);
    }

    /** Validated, resolved, and sorted query inputs shared by analyze and simulation. */
    record ParsedInputs(
            List<SystemEntity> starts, int maxDepth, Set<SystemEntityType> includeTypes) {}

    /**
     * Validates raw query input with impact-query semantics (blank/oversized
     * input fails 400, unknown identifiers 404) and returns resolved inputs.
     * Package-private so simulation validates identically without duplicating
     * the semantics.
     */
    ParsedInputs parseInputs(
            List<String> rawFrom, Integer rawMaxDepth, List<String> rawIncludeTypes) {
        if (rawFrom == null || rawFrom.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one starting entity is required (query parameter 'from')");
        }
        List<String> cleaned = new ArrayList<>();
        for (String raw : rawFrom) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                        "Starting entity identifiers must not be blank");
            }
            cleaned.add(raw.trim());
        }
        if (cleaned.size() > MAX_STARTS) {
            throw new IllegalArgumentException(
                    "At most " + MAX_STARTS + " starting entities are supported");
        }
        int maxDepth = rawMaxDepth == null ? DEFAULT_MAX_DEPTH : rawMaxDepth;
        if (maxDepth < 1 || maxDepth > MAX_DEPTH_ALLOWED) {
            throw new IllegalArgumentException(
                    "maxDepth must be between 1 and " + MAX_DEPTH_ALLOWED);
        }
        Set<SystemEntityType> includeTypes = parseIncludeTypes(rawIncludeTypes);

        List<SystemEntity> starts = new ArrayList<>();
        for (String raw : cleaned) {
            starts.add(resolve(raw));
        }
        // Deterministic start order: externalRef first, then id.
        starts.sort(
                Comparator.comparing(
                                (SystemEntity e) -> e.getExternalRef(),
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(e -> e.getId().toString()));

        return new ParsedInputs(starts, maxDepth, includeTypes);
    }

    /**
     * Runs the deterministic BFS core over caller-supplied entity and edge
     * collections (baseline snapshot or hypothetical simulation working
     * sets). Package-private so simulation reuses the traversal without
     * duplicating it; callers must have validated and resolved inputs first.
     */
    ImpactResponse analyzeResolved(
            List<SystemEntity> starts,
            int maxDepth,
            Set<SystemEntityType> includeTypes,
            List<SystemEntity> allEntities,
            List<SystemRelationship> allRelationships) {
        return analyzeGraph(
                starts,
                allEntities,
                allRelationships,
                maxDepth,
                includeTypes,
                new ImpactResponse.Limits(
                        MAX_STARTS, MAX_DEPTH_ALLOWED, MAX_NODES, MAX_PATHS));
    }

    /**
     * Resolves one UUID-or-external-reference identifier with the exact
     * semantics of the impact query (404 on unknown). Package-private so
     * simulation resolves proposal references identically.
     */
    SystemEntity resolveEntity(String raw) {
        return resolve(raw);
    }

    private SystemEntity resolve(String raw) {
        UUID id = tryParseUuid(raw);
        if (id != null) {
            SystemEntity byId = entities.findById(id).orElse(null);
            if (byId != null) {
                return byId;
            }
            // A UUID-shaped string may still be an external reference.
            SystemEntity byRef = entities.findByExternalRef(raw).orElse(null);
            if (byRef != null) {
                return byRef;
            }
            throw new EntityNotFoundException("SystemEntity not found: " + raw);
        }
        return entities
                .findByExternalRef(raw)
                .orElseThrow(() -> new EntityNotFoundException("SystemEntity not found: " + raw));
    }

    private static UUID tryParseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Set<SystemEntityType> parseIncludeTypes(List<String> rawIncludeTypes) {
        if (rawIncludeTypes == null || rawIncludeTypes.isEmpty()) {
            return EnumSet.copyOf(DEFAULT_INCLUDE_TYPES);
        }
        Set<SystemEntityType> parsed = EnumSet.noneOf(SystemEntityType.class);
        for (String raw : rawIncludeTypes) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                        "includeTypes values must not be blank");
            }
            try {
                parsed.add(SystemEntityType.valueOf(raw.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown entity type: " + raw.trim());
            }
        }
        return parsed;
    }

    /**
     * Pure deterministic BFS core: no repository access, so unit tests can
     * exercise every direction, diamond, cycle, and cap without a database.
     */
    ImpactResponse analyzeGraph(
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
                List<StepView> nextSteps = new ArrayList<>(current.steps());
                nextSteps.add(
                        StepView.of(
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
            for (StepView step : path.steps()) {
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
                            displayRef(index.get(path.targetId())),
                            path.depth(),
                            List.copyOf(steps),
                            explain(path.steps())));
        }
        paths.sort(
                Comparator.comparing(
                                ImpactResponse.ImpactPath::targetExternalRef,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparingInt(ImpactResponse.ImpactPath::depth)
                        .thenComparing(
                                p -> renderKey(p.steps()))
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

    private static String displayRef(SystemEntity entity) {
        if (entity == null) {
            return null;
        }
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

    private static String renderKey(List<ImpactResponse.ImpactStep> steps) {
        StringBuilder key = new StringBuilder();
        for (ImpactResponse.ImpactStep step : steps) {
            key.append(step.sourceExternalRef())
                    .append('|')
                    .append(step.type().name())
                    .append('|')
                    .append(step.direction().name())
                    .append('|')
                    .append(step.targetExternalRef())
                    .append(';');
        }
        return key.toString();
    }

    /**
     * Human-readable evidence sentence for one recorded path, derived only
     * from the stored steps. Unknown relationship types get a transparent
     * structural-reachability sentence — never invented domain meaning.
     */
    static String explain(List<StepView> steps) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append(explainStep(steps.get(i)));
        }
        return text.toString();
    }

    static String explainStep(StepView step) {
        String stored =
                step.storedSourceRef() + " -" + step.type().name() + "-> " + step.storedTargetRef();
        boolean reverse = step.direction() == ImpactDirection.REVERSE;
        return switch (step.type()) {
            case CALLS -> explainCalls(step, stored, reverse);
            case DEPENDS_ON -> explainDependsOn(step, stored, reverse);
            case CONTAINS -> explainContains(step, stored, reverse);
            case WRITES -> explainWrites(step, stored, reverse);
            case EXPOSES -> explainExposes(step, stored, reverse);
            default -> explainGeneric(step, stored);
        };
    }

    private static String explainCalls(StepView step, String stored, boolean reverse) {
        if (reverse) {
            return step.traversedTargetDisplay()
                    + " calls "
                    + step.traversedSourceDisplay()
                    + " ("
                    + stored
                    + "): "
                    + step.traversedTargetDisplay()
                    + " depends on "
                    + step.traversedSourceDisplay()
                    + ", so a change in "
                    + step.traversedSourceDisplay()
                    + " may affect "
                    + step.traversedTargetDisplay()
                    + ".";
        }
        return step.traversedSourceDisplay()
                + " calls "
                + step.traversedTargetDisplay()
                + " ("
                + stored
                + "): "
                + step.traversedTargetDisplay()
                + " is a downstream dependency of "
                + step.traversedSourceDisplay()
                + ", not confirmed breakage.";
    }

    private static String explainDependsOn(StepView step, String stored, boolean reverse) {
        if (reverse) {
            return step.traversedTargetDisplay()
                    + " depends on "
                    + step.traversedSourceDisplay()
                    + " ("
                    + stored
                    + "): a change in "
                    + step.traversedSourceDisplay()
                    + " may affect "
                    + step.traversedTargetDisplay()
                    + ".";
        }
        return step.traversedSourceDisplay()
                + " depends on "
                + step.traversedTargetDisplay()
                + " ("
                + stored
                + "): "
                + step.traversedTargetDisplay()
                + " is a direct dependency of "
                + step.traversedSourceDisplay()
                + ".";
    }

    private static String explainContains(StepView step, String stored, boolean reverse) {
        if (reverse) {
            return step.storedSourceDisplay()
                    + " contains "
                    + step.storedTargetDisplay()
                    + " ("
                    + stored
                    + "): a change in "
                    + step.storedTargetDisplay()
                    + " is visible in the scope of "
                    + step.storedSourceDisplay()
                    + ".";
        }
        return step.storedSourceDisplay()
                + " contains "
                + step.storedTargetDisplay()
                + " ("
                + stored
                + "): a change in "
                + step.storedSourceDisplay()
                + " may reach "
                + step.storedTargetDisplay()
                + ".";
    }

    private static String explainWrites(StepView step, String stored, boolean reverse) {
        if (reverse) {
            return step.traversedTargetDisplay()
                    + " writes "
                    + step.traversedSourceDisplay()
                    + " ("
                    + stored
                    + "): a change in "
                    + step.traversedSourceDisplay()
                    + " may affect "
                    + step.traversedTargetDisplay()
                    + ".";
        }
        return step.traversedSourceDisplay()
                + " writes "
                + step.traversedTargetDisplay()
                + " ("
                + stored
                + "): "
                + step.traversedTargetDisplay()
                + " is written by "
                + step.traversedSourceDisplay()
                + ".";
    }

    private static String explainExposes(StepView step, String stored, boolean reverse) {
        if (reverse) {
            return step.storedSourceDisplay()
                    + " exposes "
                    + step.storedTargetDisplay()
                    + " ("
                    + stored
                    + "): a change in "
                    + step.storedTargetDisplay()
                    + " traces to its owning service "
                    + step.storedSourceDisplay()
                    + ".";
        }
        return step.storedSourceDisplay()
                + " exposes "
                + step.storedTargetDisplay()
                + " ("
                + stored
                + "): a change in "
                + step.storedSourceDisplay()
                + " may change "
                + step.storedTargetDisplay()
                + ".";
    }

    private static String explainGeneric(StepView step, String stored) {
        return step.traversedSourceDisplay()
                + " is linked to "
                + step.traversedTargetDisplay()
                + " via "
                + step.type().name()
                + " (stored "
                + stored
                + ", traversed "
                + step.direction().name()
                + "): no domain meaning is defined for this relationship type;"
                + " included as structural reachability only, not as confirmed impact.";
    }

    private record Expansion(
            SystemRelationship edge, UUID neighbourId, ImpactDirection direction) {}

    private record PathState(
            UUID nodeId,
            String startRef,
            List<StepView> steps,
            SystemRelationship arrivedViaEdge,
            ImpactDirection arrivedViaDirection) {
        boolean contains(UUID id) {
            if (nodeId.equals(id)) {
                return true;
            }
            for (StepView step : steps) {
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
    static boolean isDirectedLoop(List<StepView> steps, ImpactDirection closingDirection) {
        for (StepView step : steps) {
            if (step.direction() != closingDirection) {
                return false;
            }
        }
        return true;
    }

    private record RecordedPath(
            UUID targetId, int depth, String startRef, List<StepView> steps) {}

    /**
     * One traversed step. {@code source/target} follow the traversal order
     * (where the walk came from / went to); {@code storedSource/storedTarget}
     * keep the persisted edge direction so explanations stay honest.
     */
    record StepView(
            UUID sourceId,
            String sourceRef,
            UUID targetId,
            String targetRef,
            UUID storedSourceId,
            String storedSourceRef,
            UUID storedTargetId,
            String storedTargetRef,
            com.soubhagya.systivex.twin.model.SystemRelationshipType type,
            ImpactDirection direction) {

        static StepView of(
                SystemRelationship edge,
                ImpactDirection direction,
                Map<UUID, SystemEntity> index) {
            UUID storedSource = edge.getSource().getId();
            UUID storedTarget = edge.getTarget().getId();
            String storedSourceRef = displayRef(index.get(storedSource));
            String storedTargetRef = displayRef(index.get(storedTarget));
            boolean forward = direction == ImpactDirection.FORWARD;
            return new StepView(
                    forward ? storedSource : storedTarget,
                    forward ? storedSourceRef : storedTargetRef,
                    forward ? storedTarget : storedSource,
                    forward ? storedTargetRef : storedSourceRef,
                    storedSource,
                    storedSourceRef,
                    storedTarget,
                    storedTargetRef,
                    edge.getRelationshipType(),
                    direction);
        }

        String traversedSourceDisplay() {
            return sourceRef != null ? sourceRef : sourceId.toString();
        }

        String traversedTargetDisplay() {
            return targetRef != null ? targetRef : targetId.toString();
        }

        String storedSourceDisplay() {
            return storedSourceRef != null ? storedSourceRef : storedSourceId.toString();
        }

        String storedTargetDisplay() {
            return storedTargetRef != null ? storedTargetRef : storedTargetId.toString();
        }
    }
}
