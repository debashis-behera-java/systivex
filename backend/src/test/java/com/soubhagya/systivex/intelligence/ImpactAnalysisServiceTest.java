package com.soubhagya.systivex.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.model.SystemRelationshipType;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure deterministic BFS core
 * ({@link ImpactAnalysisService#analyzeGraph}): every documented direction,
 * diamonds, cycles, disconnected nodes, multi-start, determinism, depth, caps,
 * and unknown relationship types — all without a database.
 */
class ImpactAnalysisServiceTest {

    private static final ImpactResponse.Limits DEFAULT_LIMITS =
            new ImpactResponse.Limits(10, 10, 500, 2000);
    private static final Set<SystemEntityType> ALL_TYPES =
            EnumSet.allOf(SystemEntityType.class);

    private final ImpactAnalysisService service =
            new ImpactAnalysisService(null, null);

    private static SystemEntity entity(String ref, SystemEntityType type) {
        SystemEntity entity =
                new SystemEntity(type, ref, ref, "dev", Map.of());
        try {
            Field id = SystemEntity.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(entity, UUID.nameUUIDFromBytes(ref.getBytes(StandardCharsets.UTF_8)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    private static SystemRelationship edge(
            SystemEntity source, SystemEntity target, SystemRelationshipType type) {
        return new SystemRelationship(source, target, type);
    }

    private ImpactResponse run(
            List<SystemEntity> starts,
            List<SystemEntity> all,
            List<SystemRelationship> edges,
            int maxDepth,
            Set<SystemEntityType> include) {
        return service.analyzeGraph(starts, all, edges, maxDepth, include, DEFAULT_LIMITS);
    }

    private ImpactResponse run(
            List<SystemEntity> starts,
            List<SystemEntity> all,
            List<SystemRelationship> edges,
            int maxDepth,
            Set<SystemEntityType> include,
            ImpactResponse.Limits limits) {
        return service.analyzeGraph(starts, all, edges, maxDepth, include, limits);
    }

    private static ImpactResponse.ImpactedEntity impactedBy(
            ImpactResponse response, String externalRef) {
        return response.impacted().stream()
                .filter(e -> externalRef.equals(e.externalRef()))
                .findFirst()
                .orElse(null);
    }

    private static List<ImpactResponse.ImpactPath> pathsTo(
            ImpactResponse response, String externalRef) {
        return response.paths().stream()
                .filter(p -> externalRef.equals(p.targetExternalRef()))
                .toList();
    }

    @Test
    void callsReverseFindsCallersWithMayAffectExplanation() {
        SystemEntity caller = entity("svc:caller", SystemEntityType.SERVICE);
        SystemEntity callee = entity("svc:callee", SystemEntityType.SERVICE);
        ImpactResponse response =
                run(
                        List.of(callee),
                        List.of(caller, callee),
                        List.of(edge(caller, callee, SystemRelationshipType.CALLS)),
                        6,
                        ALL_TYPES);

        ImpactResponse.ImpactedEntity impacted = impactedBy(response, "svc:caller");
        assertThat(impacted).isNotNull();
        assertThat(impacted.depth()).isEqualTo(1);
        List<ImpactResponse.ImpactPath> paths = pathsTo(response, "svc:caller");
        assertThat(paths).hasSize(1);
        ImpactResponse.ImpactStep step = paths.get(0).steps().get(0);
        assertThat(step.direction()).isEqualTo(ImpactDirection.REVERSE);
        assertThat(step.type()).isEqualTo(SystemRelationshipType.CALLS);
        assertThat(paths.get(0).explanation()).contains("may affect");
    }

    @Test
    void callsForwardListsDownstreamDependencyWithoutBreakageClaim() {
        SystemEntity caller = entity("svc:caller", SystemEntityType.SERVICE);
        SystemEntity callee = entity("svc:callee", SystemEntityType.SERVICE);
        ImpactResponse response =
                run(
                        List.of(caller),
                        List.of(caller, callee),
                        List.of(edge(caller, callee, SystemRelationshipType.CALLS)),
                        6,
                        ALL_TYPES);

        ImpactResponse.ImpactedEntity impacted = impactedBy(response, "svc:callee");
        assertThat(impacted).isNotNull();
        List<ImpactResponse.ImpactPath> paths = pathsTo(response, "svc:callee");
        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).steps().get(0).direction())
                .isEqualTo(ImpactDirection.FORWARD);
        assertThat(paths.get(0).explanation()).contains("not confirmed breakage");
        assertThat(paths.get(0).explanation()).doesNotContain("may affect svc:callee");
    }

    @Test
    void dependsOnTraversesBothDirections() {
        SystemEntity svc = entity("svc:a", SystemEntityType.SERVICE);
        SystemEntity db = entity("db:a", SystemEntityType.DATABASE);
        List<SystemEntity> all = List.of(svc, db);
        List<SystemRelationship> edges =
                List.of(edge(svc, db, SystemRelationshipType.DEPENDS_ON));

        ImpactResponse fromDb = run(List.of(db), all, edges, 6, ALL_TYPES);
        assertThat(impactedBy(fromDb, "svc:a")).isNotNull();
        assertThat(pathsTo(fromDb, "svc:a").get(0).steps().get(0).direction())
                .isEqualTo(ImpactDirection.REVERSE);
        assertThat(pathsTo(fromDb, "svc:a").get(0).explanation()).contains("may affect");

        ImpactResponse fromSvc = run(List.of(svc), all, edges, 6, ALL_TYPES);
        assertThat(impactedBy(fromSvc, "db:a")).isNotNull();
        assertThat(pathsTo(fromSvc, "db:a").get(0).steps().get(0).direction())
                .isEqualTo(ImpactDirection.FORWARD);
    }

    @Test
    void containsTraversesBothDirections() {
        SystemEntity db = entity("db:a", SystemEntityType.DATABASE);
        SystemEntity table = entity("tbl:a", SystemEntityType.TABLE);
        List<SystemEntity> all = List.of(db, table);
        List<SystemRelationship> edges =
                List.of(edge(db, table, SystemRelationshipType.CONTAINS));

        ImpactResponse fromTable = run(List.of(table), all, edges, 6, ALL_TYPES);
        assertThat(impactedBy(fromTable, "db:a")).isNotNull();
        assertThat(pathsTo(fromTable, "db:a").get(0).steps().get(0).direction())
                .isEqualTo(ImpactDirection.REVERSE);

        ImpactResponse fromDb = run(List.of(db), all, edges, 6, ALL_TYPES);
        assertThat(impactedBy(fromDb, "tbl:a")).isNotNull();
        assertThat(pathsTo(fromDb, "tbl:a").get(0).steps().get(0).direction())
                .isEqualTo(ImpactDirection.FORWARD);
    }

    @Test
    void writesTraversesBothDirections() {
        SystemEntity svc = entity("svc:a", SystemEntityType.SERVICE);
        SystemEntity table = entity("tbl:a", SystemEntityType.TABLE);
        List<SystemEntity> all = List.of(svc, table);
        List<SystemRelationship> edges =
                List.of(edge(svc, table, SystemRelationshipType.WRITES));

        ImpactResponse fromTable = run(List.of(table), all, edges, 6, ALL_TYPES);
        assertThat(impactedBy(fromTable, "svc:a")).isNotNull();
        assertThat(pathsTo(fromTable, "svc:a").get(0).steps().get(0).direction())
                .isEqualTo(ImpactDirection.REVERSE);
        assertThat(pathsTo(fromTable, "svc:a").get(0).explanation()).contains("may affect");

        ImpactResponse fromSvc = run(List.of(svc), all, edges, 6, ALL_TYPES);
        assertThat(impactedBy(fromSvc, "tbl:a")).isNotNull();
        assertThat(pathsTo(fromSvc, "tbl:a").get(0).steps().get(0).direction())
                .isEqualTo(ImpactDirection.FORWARD);
    }

    @Test
    void exposesTraversesBothDirections() {
        SystemEntity svc = entity("svc:a", SystemEntityType.SERVICE);
        SystemEntity api = entity("api:a", SystemEntityType.API);
        List<SystemEntity> all = List.of(svc, api);
        List<SystemRelationship> edges =
                List.of(edge(svc, api, SystemRelationshipType.EXPOSES));

        ImpactResponse fromApi = run(List.of(api), all, edges, 6, ALL_TYPES);
        assertThat(impactedBy(fromApi, "svc:a")).isNotNull();
        assertThat(pathsTo(fromApi, "svc:a").get(0).steps().get(0).direction())
                .isEqualTo(ImpactDirection.REVERSE);
        assertThat(pathsTo(fromApi, "svc:a").get(0).explanation())
                .contains("owning service");

        ImpactResponse fromSvc = run(List.of(svc), all, edges, 6, ALL_TYPES);
        assertThat(impactedBy(fromSvc, "api:a")).isNotNull();
        assertThat(pathsTo(fromSvc, "api:a").get(0).steps().get(0).direction())
                .isEqualTo(ImpactDirection.FORWARD);
    }

    @Test
    void diamondKeepsBothAlternativePaths() {
        SystemEntity start = entity("svc:s", SystemEntityType.SERVICE);
        SystemEntity left = entity("svc:left", SystemEntityType.SERVICE);
        SystemEntity right = entity("svc:right", SystemEntityType.SERVICE);
        SystemEntity join = entity("svc:join", SystemEntityType.SERVICE);
        ImpactResponse response =
                run(
                        List.of(start),
                        List.of(start, left, right, join),
                        List.of(
                                edge(start, left, SystemRelationshipType.CALLS),
                                edge(start, right, SystemRelationshipType.CALLS),
                                edge(left, join, SystemRelationshipType.CALLS),
                                edge(right, join, SystemRelationshipType.CALLS)),
                        6,
                        ALL_TYPES);

        // The join node was visited through the left route first, but the
        // right-route evidence must not be silently dropped.
        List<ImpactResponse.ImpactPath> paths = pathsTo(response, "svc:join");
        assertThat(paths).hasSize(2);
        assertThat(impactedBy(response, "svc:join").depth()).isEqualTo(2);
        // A diamond is not a cycle: reaching the join through two routes, and
        // the bidirectional backtracking along each edge, must not report one.
        assertThat(response.cycleEncountered()).isFalse();
    }

    @Test
    void ordinaryBacktrackingIsNotReportedAsCycle() {
        SystemEntity a = entity("svc:a", SystemEntityType.SERVICE);
        SystemEntity b = entity("svc:b", SystemEntityType.SERVICE);
        List<SystemEntity> all = List.of(a, b);
        List<SystemRelationship> edges =
                List.of(edge(a, b, SystemRelationshipType.CALLS));

        // Single stored relationship, walked bidirectionally: A -> B forward
        // and the immediate walk back B -> A over the same edge is traversal
        // mechanics, not a graph cycle.
        ImpactResponse fromA = run(List.of(a), all, edges, 6, ALL_TYPES);
        assertThat(impactedBy(fromA, "svc:b").depth()).isEqualTo(1);
        assertThat(fromA.cycleEncountered()).isFalse();
        assertThat(fromA.truncated()).isFalse();

        ImpactResponse fromB = run(List.of(b), all, edges, 6, ALL_TYPES);
        assertThat(impactedBy(fromB, "svc:a").depth()).isEqualTo(1);
        assertThat(fromB.cycleEncountered()).isFalse();
    }

    @Test
    void genuineTwoNodeCycleTerminatesAndIsReported() {
        SystemEntity a = entity("svc:a", SystemEntityType.SERVICE);
        SystemEntity b = entity("svc:b", SystemEntityType.SERVICE);
        ImpactResponse response =
                run(
                        List.of(a),
                        List.of(a, b),
                        List.of(
                                edge(a, b, SystemRelationshipType.CALLS),
                                edge(b, a, SystemRelationshipType.DEPENDS_ON)),
                        6,
                        ALL_TYPES);

        // Two distinct stored relationships forming a real loop over distinct
        // vertices: terminates via the revisit guard and reports it.
        assertThat(response.cycleEncountered()).isTrue();
        assertThat(response.truncated()).isFalse();
        assertThat(impactedBy(response, "svc:b").depth()).isEqualTo(1);
        for (ImpactResponse.ImpactPath path : response.paths()) {
            List<UUID> seen = new ArrayList<>();
            for (ImpactResponse.ImpactStep step : path.steps()) {
                assertThat(seen).doesNotContain(step.targetId());
                seen.add(step.sourceId());
                seen.add(step.targetId());
            }
        }
    }

    @Test
    void cycleTerminatesAndIsReported() {
        SystemEntity a = entity("svc:a", SystemEntityType.SERVICE);
        SystemEntity b = entity("svc:b", SystemEntityType.SERVICE);
        SystemEntity c = entity("svc:c", SystemEntityType.SERVICE);
        ImpactResponse response =
                run(
                        List.of(a),
                        List.of(a, b, c),
                        List.of(
                                edge(a, b, SystemRelationshipType.CALLS),
                                edge(b, c, SystemRelationshipType.CALLS),
                                edge(c, a, SystemRelationshipType.CALLS)),
                        6,
                        ALL_TYPES);

        assertThat(response.cycleEncountered()).isTrue();
        assertThat(response.truncated()).isFalse();
        // Bidirectional traversal: B is one forward hop (A calls B) and C is
        // one reverse hop (C calls A, so C depends on A).
        assertThat(impactedBy(response, "svc:b").depth()).isEqualTo(1);
        assertThat(impactedBy(response, "svc:c").depth()).isEqualTo(1);
        // No path may revisit a node: every recorded path is simple.
        for (ImpactResponse.ImpactPath path : response.paths()) {
            List<UUID> seen = new ArrayList<>();
            for (ImpactResponse.ImpactStep step : path.steps()) {
                assertThat(seen).doesNotContain(step.targetId());
                seen.add(step.sourceId());
                seen.add(step.targetId());
            }
        }
    }

    @Test
    void selfLoopTerminatesAndIsReported() {
        SystemEntity a = entity("svc:a", SystemEntityType.SERVICE);
        SystemEntity b = entity("svc:b", SystemEntityType.SERVICE);
        ImpactResponse response =
                run(
                        List.of(a),
                        List.of(a, b),
                        List.of(
                                edge(a, b, SystemRelationshipType.CALLS),
                                edge(b, b, SystemRelationshipType.CALLS)),
                        6,
                        ALL_TYPES);

        assertThat(response.cycleEncountered()).isTrue();
        assertThat(impactedBy(response, "svc:b")).isNotNull();
    }

    @Test
    void disconnectedStartReturnsSelfOnly() {
        SystemEntity lonely = entity("svc:lonely", SystemEntityType.SERVICE);
        SystemEntity other = entity("svc:other", SystemEntityType.SERVICE);
        ImpactResponse response =
                run(
                        List.of(lonely),
                        List.of(lonely, other),
                        List.of(),
                        6,
                        ALL_TYPES);

        assertThat(response.startedFrom()).hasSize(1);
        assertThat(response.impacted()).isEmpty();
        assertThat(response.paths()).isEmpty();
        assertThat(response.nodeCount()).isEqualTo(1);
        assertThat(response.cycleEncountered()).isFalse();
        assertThat(response.truncated()).isFalse();
    }

    @Test
    void multipleStartsUnionResults() {
        SystemEntity s1 = entity("svc:s1", SystemEntityType.SERVICE);
        SystemEntity s2 = entity("svc:s2", SystemEntityType.SERVICE);
        SystemEntity t1 = entity("svc:t1", SystemEntityType.SERVICE);
        SystemEntity t2 = entity("svc:t2", SystemEntityType.SERVICE);
        ImpactResponse response =
                run(
                        List.of(s1, s2),
                        List.of(s1, s2, t1, t2),
                        List.of(
                                edge(s1, t1, SystemRelationshipType.CALLS),
                                edge(s2, t2, SystemRelationshipType.CALLS)),
                        6,
                        ALL_TYPES);

        assertThat(impactedBy(response, "svc:t1")).isNotNull();
        assertThat(impactedBy(response, "svc:t2")).isNotNull();
        assertThat(impactedBy(response, "svc:t1").viaStart()).isEqualTo("svc:s1");
        assertThat(impactedBy(response, "svc:t2").viaStart()).isEqualTo("svc:s2");
        // Starts are depth-zero nodes, never impacted.
        assertThat(impactedBy(response, "svc:s1")).isNull();
        assertThat(impactedBy(response, "svc:s2")).isNull();
    }

    @Test
    void outputIsDeterministicAcrossRunsAndInputOrder() {
        SystemEntity s1 = entity("svc:s1", SystemEntityType.SERVICE);
        SystemEntity s2 = entity("svc:s2", SystemEntityType.SERVICE);
        SystemEntity mid = entity("svc:mid", SystemEntityType.SERVICE);
        SystemEntity leaf = entity("svc:leaf", SystemEntityType.SERVICE);
        List<SystemEntity> all = List.of(s1, s2, mid, leaf);
        List<SystemRelationship> edges =
                List.of(
                        edge(s1, mid, SystemRelationshipType.CALLS),
                        edge(s2, mid, SystemRelationshipType.CALLS),
                        edge(mid, leaf, SystemRelationshipType.CALLS));

        ImpactResponse first = run(List.of(s1, s2), all, edges, 6, ALL_TYPES);
        ImpactResponse second = run(List.of(s2, s1), all, edges, 6, ALL_TYPES);

        assertThat(renderStable(second)).isEqualTo(renderStable(first));
    }

    private static String renderStable(ImpactResponse response) {
        StringBuilder rendered = new StringBuilder();
        for (ImpactResponse.ImpactedEntity entity : response.impacted()) {
            rendered.append(entity.id()).append(entity.depth()).append(entity.viaStart());
        }
        for (ImpactResponse.ImpactPath path : response.paths()) {
            rendered.append(path.targetId()).append(path.explanation());
            for (ImpactResponse.ImpactStep step : path.steps()) {
                rendered.append(step.sourceId())
                        .append(step.type())
                        .append(step.direction())
                        .append(step.targetId());
            }
        }
        return rendered.toString();
    }

    @Test
    void maxDepthBoundsTraversal() {
        SystemEntity s = entity("svc:s", SystemEntityType.SERVICE);
        SystemEntity m1 = entity("svc:m1", SystemEntityType.SERVICE);
        SystemEntity m2 = entity("svc:m2", SystemEntityType.SERVICE);
        SystemEntity m3 = entity("svc:m3", SystemEntityType.SERVICE);
        List<SystemEntity> all = List.of(s, m1, m2, m3);
        List<SystemRelationship> edges =
                List.of(
                        edge(s, m1, SystemRelationshipType.CALLS),
                        edge(m1, m2, SystemRelationshipType.CALLS),
                        edge(m2, m3, SystemRelationshipType.CALLS));

        ImpactResponse response = run(List.of(s), all, edges, 2, ALL_TYPES);

        assertThat(response.maxDepth()).isEqualTo(2);
        assertThat(impactedBy(response, "svc:m1").depth()).isEqualTo(1);
        assertThat(impactedBy(response, "svc:m2").depth()).isEqualTo(2);
        assertThat(impactedBy(response, "svc:m3")).isNull();
        assertThat(pathsTo(response, "svc:m3")).isEmpty();
    }

    @Test
    void pathCapTruncatesExplicitly() {
        SystemEntity start = entity("svc:s", SystemEntityType.SERVICE);
        List<SystemEntity> all = new ArrayList<>();
        all.add(start);
        List<SystemRelationship> edges = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            SystemEntity leaf = entity("svc:leaf-" + i, SystemEntityType.SERVICE);
            all.add(leaf);
            edges.add(edge(start, leaf, SystemRelationshipType.CALLS));
        }
        ImpactResponse response =
                run(
                        List.of(start),
                        all,
                        edges,
                        6,
                        ALL_TYPES,
                        new ImpactResponse.Limits(10, 10, 500, 3));

        assertThat(response.truncated()).isTrue();
        assertThat(response.truncationReason()).isEqualTo("path-cap");
        assertThat(response.paths()).hasSize(3);
    }

    @Test
    void nodeCapTruncatesExplicitly() {
        SystemEntity start = entity("svc:s", SystemEntityType.SERVICE);
        List<SystemEntity> all = new ArrayList<>();
        all.add(start);
        List<SystemRelationship> edges = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            SystemEntity leaf = entity("svc:leaf-" + i, SystemEntityType.SERVICE);
            all.add(leaf);
            edges.add(edge(start, leaf, SystemRelationshipType.CALLS));
        }
        ImpactResponse response =
                run(
                        List.of(start),
                        all,
                        edges,
                        6,
                        ALL_TYPES,
                        new ImpactResponse.Limits(10, 10, 3, 2000));

        assertThat(response.truncated()).isTrue();
        assertThat(response.truncationReason()).isEqualTo("node-cap");
        // 1 start + at most 2 further nodes within the cap of 3.
        assertThat(response.nodeCount()).isLessThanOrEqualTo(3);
    }

    @Test
    void unknownRelationshipTypeHasNoInventedSemantics() {
        SystemEntity svc = entity("svc:a", SystemEntityType.SERVICE);
        SystemEntity db = entity("db:a", SystemEntityType.DATABASE);
        ImpactResponse response =
                run(
                        List.of(svc),
                        List.of(svc, db),
                        List.of(edge(svc, db, SystemRelationshipType.READS)),
                        6,
                        ALL_TYPES);

        assertThat(impactedBy(response, "db:a")).isNotNull();
        List<ImpactResponse.ImpactPath> paths = pathsTo(response, "db:a");
        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).steps().get(0).type())
                .isEqualTo(SystemRelationshipType.READS);
        assertThat(paths.get(0).explanation())
                .contains("no domain meaning is defined for this relationship type");
        assertThat(paths.get(0).explanation()).contains("structural reachability only");
    }

    @Test
    void includeTypesFiltersOutputButNotTraversal() {
        SystemEntity table = entity("tbl:t", SystemEntityType.TABLE);
        SystemEntity db = entity("db:d", SystemEntityType.DATABASE);
        SystemEntity svc = entity("svc:s", SystemEntityType.SERVICE);
        List<SystemEntity> all = List.of(table, db, svc);
        List<SystemRelationship> edges =
                List.of(
                        edge(db, table, SystemRelationshipType.CONTAINS),
                        edge(svc, db, SystemRelationshipType.DEPENDS_ON));

        ImpactResponse response =
                run(
                        List.of(table),
                        all,
                        edges,
                        6,
                        EnumSet.of(SystemEntityType.SERVICE));

        // The database is traversed through but filtered from the output;
        // the service behind it is still found with a complete evidence path.
        assertThat(impactedBy(response, "db:d")).isNull();
        assertThat(impactedBy(response, "svc:s").depth()).isEqualTo(2);
        List<ImpactResponse.ImpactPath> paths = pathsTo(response, "svc:s");
        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).steps()).hasSize(2);
    }

    @Test
    void everyImpactedEntityHasAtLeastOneCompletePath() {
        SystemEntity s = entity("svc:s", SystemEntityType.SERVICE);
        SystemEntity a = entity("svc:a", SystemEntityType.SERVICE);
        SystemEntity b = entity("svc:b", SystemEntityType.SERVICE);
        ImpactResponse response =
                run(
                        List.of(s),
                        List.of(s, a, b),
                        List.of(
                                edge(s, a, SystemRelationshipType.CALLS),
                                edge(a, b, SystemRelationshipType.CALLS),
                                edge(s, b, SystemRelationshipType.CALLS)),
                        6,
                        ALL_TYPES);

        for (ImpactResponse.ImpactedEntity impacted : response.impacted()) {
            List<ImpactResponse.ImpactPath> paths =
                    pathsTo(response, impacted.externalRef());
            assertThat(paths)
                    .as("evidence paths for %s", impacted.externalRef())
                    .isNotEmpty();
            for (ImpactResponse.ImpactPath path : paths) {
                assertThat(path.steps()).isNotEmpty();
                assertThat(path.depth()).isEqualTo(path.steps().size());
                assertThat(path.explanation()).isNotBlank();
            }
        }
    }
}
