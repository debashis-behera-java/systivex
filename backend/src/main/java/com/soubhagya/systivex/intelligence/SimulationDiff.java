package com.soubhagya.systivex.intelligence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure before/after differ: deterministic impact/risk deltas plus the
 * explicit limitation lines every simulation response carries.
 *
 * <p>No repository access, no Spring, no I/O. Added/removed references and
 * risk-level changes are sorted, so identical inputs always render
 * byte-identical deltas (modulo the timestamped snapshots they derive
 * from).
 */
public final class SimulationDiff {

    private SimulationDiff() {}

    /** Deterministic delta between the baseline and hypothetical scopes. */
    public static SimulationDelta diff(
            ImpactResponse beforeImpact,
            ImpactResponse afterImpact,
            RiskResponse beforeRisk,
            RiskResponse afterRisk) {
        Set<UUID> beforeIds = new HashSet<>();
        for (ImpactResponse.ImpactedEntity e : beforeImpact.impacted()) {
            beforeIds.add(e.id());
        }
        Set<UUID> afterIds = new HashSet<>();
        for (ImpactResponse.ImpactedEntity e : afterImpact.impacted()) {
            afterIds.add(e.id());
        }
        List<String> addedRefs = new ArrayList<>();
        for (ImpactResponse.ImpactedEntity e : afterImpact.impacted()) {
            if (!beforeIds.contains(e.id())) {
                addedRefs.add(displayRef(e.externalRef(), e.id()));
            }
        }
        List<String> removedRefs = new ArrayList<>();
        for (ImpactResponse.ImpactedEntity e : beforeImpact.impacted()) {
            if (!afterIds.contains(e.id())) {
                removedRefs.add(displayRef(e.externalRef(), e.id()));
            }
        }
        addedRefs.sort(String::compareTo);
        removedRefs.sort(String::compareTo);

        Map<UUID, RiskResponse.RiskEntry> beforeRiskMap = new HashMap<>();
        for (RiskResponse.RiskEntry r : beforeRisk.risks()) {
            beforeRiskMap.put(r.id(), r);
        }
        Map<UUID, RiskResponse.RiskEntry> afterRiskMap = new HashMap<>();
        for (RiskResponse.RiskEntry r : afterRisk.risks()) {
            afterRiskMap.put(r.id(), r);
        }
        int riskAdded = 0;
        int riskRemoved = 0;
        List<SimulationDelta.RiskLevelChange> levelChanges = new ArrayList<>();
        for (Map.Entry<UUID, RiskResponse.RiskEntry> entry : afterRiskMap.entrySet()) {
            RiskResponse.RiskEntry before = beforeRiskMap.get(entry.getKey());
            if (before == null) {
                riskAdded++;
            } else if (before.level() != entry.getValue().level()) {
                levelChanges.add(
                        new SimulationDelta.RiskLevelChange(
                                displayRef(entry.getValue().externalRef(), entry.getKey()),
                                before.level(),
                                entry.getValue().level()));
            }
        }
        for (UUID id : beforeRiskMap.keySet()) {
            if (!afterRiskMap.containsKey(id)) {
                riskRemoved++;
            }
        }
        levelChanges.sort(Comparator.comparing(SimulationDelta.RiskLevelChange::entityRef));

        long uncertainBefore = countUncertain(beforeRisk);
        long uncertainAfter = countUncertain(afterRisk);

        return new SimulationDelta(
                beforeImpact.impacted().size(),
                afterImpact.impacted().size(),
                addedRefs.size(),
                removedRefs.size(),
                List.copyOf(addedRefs),
                List.copyOf(removedRefs),
                beforeRisk.risks().size(),
                afterRisk.risks().size(),
                riskAdded,
                riskRemoved,
                List.copyOf(levelChanges),
                (int) uncertainBefore,
                (int) uncertainAfter,
                truncationChanged(beforeImpact, afterImpact));
    }

    /** Explicit truncation/uncertainty limitation lines for the response. */
    public static List<String> limitations(
            String advisory,
            ImpactResponse beforeImpact,
            ImpactResponse afterImpact,
            RiskResponse beforeRisk,
            RiskResponse afterRisk) {
        List<String> lines = new ArrayList<>();
        lines.add(advisory + ": hypothetical impact and risk only; no proposal,"
                + " entity, relationship, or runtime state was persisted.");
        lines.add("Runtime telemetry is reused unchanged; no runtime behaviour is simulated.");
        if (beforeImpact.truncated()) {
            lines.add(
                    "Baseline impact traversal truncated: "
                            + beforeImpact.truncationReason()
                            + " — the baseline is partial, so deltas understate the change.");
        }
        if (afterImpact.truncated()) {
            lines.add(
                    "Simulated impact traversal truncated: "
                            + afterImpact.truncationReason()
                            + " — the hypothetical result is partial,"
                            + " so deltas understate the change.");
        }
        long uncertainBefore = countUncertain(beforeRisk);
        if (uncertainBefore > 0) {
            lines.add(
                    uncertainBefore
                            + " baseline impacted components carry uncertain"
                            + " (missing or stale) runtime evidence.");
        }
        long uncertainAfter = countUncertain(afterRisk);
        if (uncertainAfter > 0) {
            lines.add(
                    uncertainAfter
                            + " simulated impacted components carry uncertain"
                            + " (missing or stale) runtime evidence.");
        }
        lines.add(
                "Caps and truncation may hide additional affected components;"
                        + " re-run with narrower roots or a smaller depth when truncated.");
        return List.copyOf(lines);
    }

    private static long countUncertain(RiskResponse risk) {
        return risk.risks().stream().filter(RiskResponse.RiskEntry::uncertain).count();
    }

    private static boolean truncationChanged(ImpactResponse before, ImpactResponse after) {
        return before.truncated() != after.truncated()
                || !Objects.equals(before.truncationReason(), after.truncationReason());
    }

    private static String displayRef(String externalRef, UUID id) {
        return externalRef != null ? externalRef : id.toString();
    }
}
