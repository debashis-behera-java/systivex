package com.soubhagya.systivex.intelligence;

import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.ALL_TYPES;
import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.edge;
import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.entity;
import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.impactedBy;
import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.pathsTo;
import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.renderStable;
import static com.soubhagya.systivex.intelligence.ImpactTestFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.model.SystemRelationshipType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Traversal bounds and output shape: disconnected nodes, multi-start,
 * determinism, depth, caps, type filtering, and evidence completeness —
 * all without a database.
 */
class ImpactTraversalBoundsTest {

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
