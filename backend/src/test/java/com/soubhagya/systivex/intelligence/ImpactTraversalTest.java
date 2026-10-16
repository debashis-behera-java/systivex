package com.soubhagya.systivex.intelligence;

import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.ALL_TYPES;
import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.edge;
import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.entity;
import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.impactedBy;
import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.pathsTo;
import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.model.SystemRelationshipType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Traversal semantics: every documented direction, diamonds, cycles, and
 * backtracking — all without a database.
 */
class ImpactTraversalTest {

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
}
