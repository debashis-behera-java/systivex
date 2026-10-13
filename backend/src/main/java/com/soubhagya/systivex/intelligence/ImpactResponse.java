package com.soubhagya.systivex.intelligence;

import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.model.SystemRelationshipType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read shape of one deterministic change-impact analysis run.
 *
 * <p>Every impacted entity carries at least one complete evidence path in
 * {@code paths}: ordered steps over persisted relationships, each recording
 * the traversal direction actually taken. Starting entities are listed
 * separately in {@code startedFrom} (depth zero) and never counted as
 * impacted. Results are "potentially affected" components, not confirmed
 * breakage — see {@code note}.
 */
public record ImpactResponse(
        List<StartEntity> startedFrom,
        List<ImpactedEntity> impacted,
        List<ImpactPath> paths,
        int maxDepth,
        int nodeCount,
        int pathCount,
        boolean truncated,
        String truncationReason,
        boolean cycleEncountered,
        Instant snapshotObservedAt,
        int entityCount,
        int relationshipCount,
        Limits limits,
        String note) {

    public record StartEntity(
            UUID id,
            SystemEntityType type,
            String name,
            String externalRef,
            String environment) {}

    public record ImpactedEntity(
            UUID id,
            SystemEntityType type,
            String name,
            String externalRef,
            int depth,
            String viaStart) {}

    public record ImpactPath(
            UUID targetId,
            String targetExternalRef,
            int depth,
            List<ImpactStep> steps,
            String explanation) {}

    public record ImpactStep(
            UUID sourceId,
            String sourceExternalRef,
            UUID targetId,
            String targetExternalRef,
            SystemRelationshipType type,
            ImpactDirection direction) {}

    public record Limits(int maxStarts, int maxDepthAllowed, int maxNodes, int maxPaths) {}
}
