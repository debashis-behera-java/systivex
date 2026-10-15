package com.soubhagya.systivex.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.twin.model.SystemEntityType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure before/after differ ({@link SimulationDiff}):
 * deterministic impact/risk deltas, sorted references and level changes,
 * the truncation-changed matrix, uncertainty counts, and the explicit
 * limitation lines (advisory, truncation visibility, runtime reuse, caps).
 * No database, no Spring.
 */
class SimulationDiffTest {

    private static final ImpactResponse.Limits LIMITS =
            new ImpactResponse.Limits(10, 10, 500, 2000);

    private static ImpactResponse.ImpactedEntity impacted(UUID id, String ref) {
        return new ImpactResponse.ImpactedEntity(
                id, SystemEntityType.SERVICE, ref, ref, 1, "root");
    }

    private static ImpactResponse impact(
            boolean truncated, String reason, ImpactResponse.ImpactedEntity... entities) {
        return new ImpactResponse(
                List.of(), List.of(entities), List.of(), 6, entities.length, 0,
                truncated, reason, false, Instant.EPOCH, 3, 2, LIMITS, "note");
    }

    private static RiskResponse.RiskEntry entry(
            UUID id, String ref, RiskLevel level, boolean uncertain) {
        return new RiskResponse.RiskEntry(
                id, "SERVICE", ref, ref, 1, "root", level, List.of("reason"),
                uncertain, uncertain);
    }

    private static RiskResponse risk(RiskResponse.RiskEntry... entries) {
        return new RiskResponse(
                List.of(), List.of(entries), 6, "risk-v1", Instant.EPOCH, 3, 2, "note");
    }

    @Test
    void emptyVersusEmptyDiffIsZero() {
        SimulationDelta delta =
                SimulationDiff.diff(impact(false, null), impact(false, null), risk(), risk());

        assertThat(delta.beforeImpactedCount()).isZero();
        assertThat(delta.afterImpactedCount()).isZero();
        assertThat(delta.impactedAdded()).isEmpty();
        assertThat(delta.impactedRemoved()).isEmpty();
        assertThat(delta.riskLevelChanges()).isEmpty();
        assertThat(delta.truncationChanged()).isFalse();
        assertThat(delta.uncertainBeforeCount()).isZero();
        assertThat(delta.uncertainAfterCount()).isZero();
    }

    @Test
    void addedAndRemovedRefsAreSortedDeterministically() {
        UUID keep = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID freshB = UUID.randomUUID();
        UUID freshA = UUID.randomUUID();
        ImpactResponse before =
                impact(false, null, impacted(keep, "svc:keep"), impacted(gone, "svc:gone"));
        // Fresh refs added in reverse alphabetical insertion order.
        ImpactResponse after =
                impact(
                        false, null,
                        impacted(keep, "svc:keep"),
                        impacted(freshB, "svc:zzz"),
                        impacted(freshA, "svc:aaa"));

        SimulationDelta delta = SimulationDiff.diff(before, after, risk(), risk());

        assertThat(delta.beforeImpactedCount()).isEqualTo(2);
        assertThat(delta.afterImpactedCount()).isEqualTo(3);
        assertThat(delta.impactedAddedCount()).isEqualTo(2);
        assertThat(delta.impactedRemovedCount()).isEqualTo(1);
        assertThat(delta.impactedAdded()).containsExactly("svc:aaa", "svc:zzz");
        assertThat(delta.impactedRemoved()).containsExactly("svc:gone");
        // Re-diffing the same inputs renders the identical delta.
        assertThat(SimulationDiff.diff(before, after, risk(), risk())).isEqualTo(delta);
    }

    @Test
    void riskMembershipAndLevelChanges() {
        UUID keep = UUID.randomUUID();
        UUID escalated = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();
        UUID joined = UUID.randomUUID();
        RiskResponse before =
                risk(
                        entry(keep, "svc:keep", RiskLevel.LOW, false),
                        entry(escalated, "svc:core", RiskLevel.MEDIUM, false),
                        entry(dropped, "svc:old", RiskLevel.LOW, false));
        RiskResponse after =
                risk(
                        entry(keep, "svc:keep", RiskLevel.LOW, false),
                        entry(escalated, "svc:core", RiskLevel.HIGH, false),
                        entry(joined, "svc:new", RiskLevel.MEDIUM, true));

        SimulationDelta delta =
                SimulationDiff.diff(
                        impact(false, null), impact(false, null), before, after);

        assertThat(delta.beforeRiskCount()).isEqualTo(3);
        assertThat(delta.afterRiskCount()).isEqualTo(3);
        assertThat(delta.riskAddedCount()).isEqualTo(1);
        assertThat(delta.riskRemovedCount()).isEqualTo(1);
        assertThat(delta.riskLevelChanges())
                .containsExactly(
                        new SimulationDelta.RiskLevelChange(
                                "svc:core", RiskLevel.MEDIUM, RiskLevel.HIGH));
        assertThat(delta.uncertainBeforeCount()).isZero();
        assertThat(delta.uncertainAfterCount()).isEqualTo(1);
    }

    @Test
    void truncationChangedMatrix() {
        assertThat(
                        SimulationDiff.diff(
                                        impact(false, null), impact(false, null), risk(), risk())
                                .truncationChanged())
                .isFalse();
        assertThat(
                        SimulationDiff.diff(
                                        impact(false, null),
                                        impact(true, "node-cap"),
                                        risk(),
                                        risk())
                                .truncationChanged())
                .isTrue();
        assertThat(
                        SimulationDiff.diff(
                                        impact(true, "node-cap"),
                                        impact(true, "node-cap"),
                                        risk(),
                                        risk())
                                .truncationChanged())
                .isFalse();
        assertThat(
                        SimulationDiff.diff(
                                        impact(true, "node-cap"),
                                        impact(true, "path-cap"),
                                        risk(),
                                        risk())
                                .truncationChanged())
                .isTrue();
    }

    @Test
    void limitationsAlwaysCarryAdvisoryRuntimeAndCapsLines() {
        List<String> lines =
                SimulationDiff.limitations(
                        SimulationService.ADVISORY,
                        impact(false, null),
                        impact(false, null),
                        risk(),
                        risk());

        assertThat(lines.get(0)).contains("SIMULATED — NOT EXECUTED");
        assertThat(lines.get(0)).contains("no proposal");
        assertThat(String.join(" ", lines)).contains("Runtime telemetry is reused unchanged");
        assertThat(lines.get(lines.size() - 1)).contains("Caps and truncation may hide");
        assertThat(String.join(" ", lines)).doesNotContain("truncated:");
    }

    @Test
    void limitationsExposeTruncationAndUncertainty() {
        UUID uncertain = UUID.randomUUID();
        List<String> lines =
                SimulationDiff.limitations(
                        SimulationService.ADVISORY,
                        impact(true, "node-cap"),
                        impact(false, null),
                        risk(entry(uncertain, "svc:foggy", RiskLevel.UNKNOWN, true)),
                        risk());

        String joined = String.join(" ", lines);
        assertThat(joined).contains("Baseline impact traversal truncated: node-cap");
        assertThat(joined).contains("1 baseline impacted components carry uncertain");
        assertThat(joined).doesNotContain("Simulated impact traversal truncated");
        assertThat(joined).doesNotContain("0 baseline");
    }
}
