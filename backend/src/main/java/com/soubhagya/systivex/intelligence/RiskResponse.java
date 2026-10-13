package com.soubhagya.systivex.intelligence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read shape of one deterministic change-risk assessment run.
 *
 * <p>Each entry explains itself: an ordinal {@link RiskLevel}, sorted
 * human-readable reasons naming the taxonomy signals that fired, and two
 * explicit flags — {@code uncertain} (evidence missing or stale) and
 * {@code stale} (runtime evidence older than the taxonomy threshold, or
 * absent for a service). Starts are listed separately at depth zero and
 * never assessed. The assessment is advisory only (see {@code note}).
 */
public record RiskResponse(
        List<ImpactResponse.StartEntity> startedFrom,
        List<RiskEntry> risks,
        int maxDepth,
        String taxonomyVersion,
        Instant snapshotObservedAt,
        int entityCount,
        int relationshipCount,
        String note) {

    public record RiskEntry(
            UUID id,
            String type,
            String name,
            String externalRef,
            int depth,
            String viaStart,
            RiskLevel level,
            List<String> reasons,
            boolean uncertain,
            boolean stale) {}
}
