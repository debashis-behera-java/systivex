package com.soubhagya.systivex.intelligence;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.model.SystemRelationshipType;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared in-memory fixtures for traversal unit tests: deterministic
 * entities (UUIDs derived from refs), edges, and response helpers — all
 * without a database.
 */
final class ImpactTestFixtures {

    static final ImpactResponse.Limits DEFAULT_LIMITS =
            new ImpactResponse.Limits(10, 10, 500, 2000);
    static final Set<SystemEntityType> ALL_TYPES =
            EnumSet.allOf(SystemEntityType.class);

    private ImpactTestFixtures() {}

    static SystemEntity entity(String ref, SystemEntityType type) {
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

    static SystemRelationship edge(
            SystemEntity source, SystemEntity target, SystemRelationshipType type) {
        return new SystemRelationship(source, target, type);
    }

    static ImpactResponse run(
            List<SystemEntity> starts,
            List<SystemEntity> all,
            List<SystemRelationship> edges,
            int maxDepth,
            Set<SystemEntityType> include) {
        return ImpactTraversal.analyzeGraph(starts, all, edges, maxDepth, include, DEFAULT_LIMITS);
    }

    static ImpactResponse run(
            List<SystemEntity> starts,
            List<SystemEntity> all,
            List<SystemRelationship> edges,
            int maxDepth,
            Set<SystemEntityType> include,
            ImpactResponse.Limits limits) {
        return ImpactTraversal.analyzeGraph(starts, all, edges, maxDepth, include, limits);
    }

    static ImpactResponse.ImpactedEntity impactedBy(
            ImpactResponse response, String externalRef) {
        return response.impacted().stream()
                .filter(e -> externalRef.equals(e.externalRef()))
                .findFirst()
                .orElse(null);
    }

    static List<ImpactResponse.ImpactPath> pathsTo(
            ImpactResponse response, String externalRef) {
        return response.paths().stream()
                .filter(p -> externalRef.equals(p.targetExternalRef()))
                .toList();
    }

    static String renderStable(ImpactResponse response) {
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
}
