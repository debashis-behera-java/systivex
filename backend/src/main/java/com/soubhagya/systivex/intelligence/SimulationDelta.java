package com.soubhagya.systivex.intelligence;

import java.util.List;
import java.util.UUID;

public record SimulationDelta(
        int beforeImpactedCount,
        int afterImpactedCount,
        int impactedAddedCount,
        int impactedRemovedCount,
        List<String> impactedAdded,
        List<String> impactedRemoved,
        int beforeRiskCount,
        int afterRiskCount,
        int riskAddedCount,
        int riskRemovedCount,
        List<RiskLevelChange> riskLevelChanges,
        int uncertainBeforeCount,
        int uncertainAfterCount,
        boolean truncationChanged) {

    public record RiskLevelChange(String entityRef, RiskLevel before, RiskLevel after) {}
}
