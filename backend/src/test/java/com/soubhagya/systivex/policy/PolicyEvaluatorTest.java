package com.soubhagya.systivex.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.intelligence.RiskLevel;
import com.soubhagya.systivex.intelligence.RiskResponse;
import com.soubhagya.systivex.intelligence.SimulationDelta;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure policy evaluator ({@link PolicyEvaluator}):
 * every {@code policy-v1} rule, the precise {@code RISK_INCREASED}
 * definition against the real {@link SimulationDelta} contract, DENY over
 * REVIEW precedence, sorted reporting, and determinism. No database, no
 * Spring.
 */
class PolicyEvaluatorTest {

    private static RiskResponse.RiskEntry entry(
            String ref, RiskLevel level, boolean uncertain) {
        return new RiskResponse.RiskEntry(
                UUID.randomUUID(), "SERVICE", ref, ref, 1, "root", level,
                List.of("reason"), uncertain, uncertain);
    }

    private static SimulationDelta delta(
            int riskAdded,
            List<SimulationDelta.RiskLevelChange> changes,
            int uncertainAfter) {
        return new SimulationDelta(
                1, 1, 0, 0, List.of(), List.of(), 1, 1,
                riskAdded, 0, changes, 0, uncertainAfter, false);
    }

    private static SimulationDelta.RiskLevelChange change(
            String ref, RiskLevel before, RiskLevel after) {
        return new SimulationDelta.RiskLevelChange(ref, before, after);
    }

    private static PolicyEvaluator.Evaluation evaluate(
            List<RiskResponse.RiskEntry> risks, SimulationDelta delta, boolean truncated) {
        return PolicyEvaluator.evaluate(risks, delta, truncated);
    }

    private static List<String> codes(PolicyEvaluator.Evaluation evaluation) {
        List<String> codes = new ArrayList<>();
        for (PolicyEvaluator.FiredRule rule : evaluation.firedRules()) {
            codes.add(rule.code());
        }
        return codes;
    }

    @Test
    void emptyResultAllowsWithNoRules() {
        PolicyEvaluator.Evaluation evaluation =
                evaluate(List.of(), delta(0, List.of(), 0), false);

        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(evaluation.firedRules()).isEmpty();
    }

    @Test
    void highRiskRemainingDenies() {
        PolicyEvaluator.Evaluation evaluation =
                evaluate(
                        List.of(entry("svc:core", RiskLevel.HIGH, false)),
                        delta(0, List.of(), 0),
                        false);

        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(codes(evaluation)).containsExactly(PolicyTaxonomy.HIGH_RISK_REMAINS);
        assertThat(evaluation.firedRules().get(0).outcome())
                .isEqualTo(PolicyDecision.DENY);
        assertThat(evaluation.firedRules().get(0).detail()).contains("1");
    }

    @Test
    void truncationDenies() {
        PolicyEvaluator.Evaluation evaluation =
                evaluate(
                        List.of(entry("svc:a", RiskLevel.LOW, false)),
                        delta(0, List.of(), 0),
                        true);

        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(codes(evaluation)).containsExactly(PolicyTaxonomy.RESULT_TRUNCATED);
    }

    @Test
    void newlyImpactedRisksReview() {
        PolicyEvaluator.Evaluation evaluation =
                evaluate(
                        List.of(entry("svc:new", RiskLevel.LOW, false)),
                        delta(1, List.of(), 0),
                        false);

        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.REVIEW);
        assertThat(codes(evaluation)).containsExactly(PolicyTaxonomy.RISK_INCREASED);
        assertThat(evaluation.firedRules().get(0).detail()).contains("1 newly impacted");
    }

    @Test
    void upwardLevelChangesReview() {
        PolicyEvaluator.Evaluation evaluation =
                evaluate(
                        List.of(entry("svc:core", RiskLevel.MEDIUM, false)),
                        delta(
                                0,
                                List.of(change("svc:core", RiskLevel.LOW, RiskLevel.MEDIUM)),
                                0),
                        false);

        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.REVIEW);
        assertThat(codes(evaluation)).containsExactly(PolicyTaxonomy.RISK_INCREASED);
        assertThat(evaluation.firedRules().get(0).detail()).contains("svc:core");
    }

    @Test
    void arrivalAtHighDeniesWithBothRulesReported() {
        // MEDIUM -> HIGH: increase (REVIEW rule fires) and HIGH remains
        // (DENY rule fires); precedence decides DENY while both are reported.
        PolicyEvaluator.Evaluation evaluation =
                evaluate(
                        List.of(entry("svc:core", RiskLevel.HIGH, false)),
                        delta(
                                0,
                                List.of(change("svc:core", RiskLevel.MEDIUM, RiskLevel.HIGH)),
                                0),
                        false);

        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(codes(evaluation))
                .containsExactly(
                        PolicyTaxonomy.HIGH_RISK_REMAINS, PolicyTaxonomy.RISK_INCREASED);
    }

    @Test
    void unknownFromNonHighIsUncertaintyNotIncrease() {
        // UNKNOWN -> MEDIUM resolves evidence without reaching HIGH: not an
        // increase, but UNKNOWN/MEDIUM-handling rules still see it.
        PolicyEvaluator.Evaluation evaluation =
                evaluate(
                        List.of(entry("svc:foggy", RiskLevel.MEDIUM, false)),
                        delta(
                                0,
                                List.of(
                                        change(
                                                "svc:foggy",
                                                RiskLevel.UNKNOWN,
                                                RiskLevel.MEDIUM)),
                                0),
                        false);

        assertThat(codes(evaluation)).doesNotContain(PolicyTaxonomy.RISK_INCREASED);
        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void downwardChangeIsNotAnIncrease() {
        PolicyEvaluator.Evaluation evaluation =
                evaluate(
                        List.of(entry("svc:calm", RiskLevel.LOW, false)),
                        delta(
                                0,
                                List.of(change("svc:calm", RiskLevel.HIGH, RiskLevel.LOW)),
                                0),
                        false);

        // HIGH -> LOW: after-scope has no HIGH, and the move is downward.
        assertThat(codes(evaluation)).doesNotContain(PolicyTaxonomy.RISK_INCREASED);
        assertThat(codes(evaluation)).doesNotContain(PolicyTaxonomy.HIGH_RISK_REMAINS);
        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void unknownRiskPresentReviews() {
        PolicyEvaluator.Evaluation evaluation =
                evaluate(
                        List.of(entry("svc:foggy", RiskLevel.UNKNOWN, true)),
                        delta(0, List.of(), 1),
                        false);

        // Both UNKNOWN and uncertainty rules fire; sorted by code.
        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.REVIEW);
        assertThat(codes(evaluation))
                .containsExactly(
                        PolicyTaxonomy.EVIDENCE_UNCERTAIN,
                        PolicyTaxonomy.UNKNOWN_RISK_PRESENT);
    }

    @Test
    void uncertainEvidenceReviews() {
        PolicyEvaluator.Evaluation evaluation =
                evaluate(
                        List.of(entry("svc:stale", RiskLevel.MEDIUM, true)),
                        delta(0, List.of(), 2),
                        false);

        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.REVIEW);
        assertThat(codes(evaluation)).containsExactly(PolicyTaxonomy.EVIDENCE_UNCERTAIN);
        assertThat(evaluation.firedRules().get(0).detail()).contains("2");
    }

    @Test
    void denyBeatsReviewWithAllRulesReportedSorted() {
        PolicyEvaluator.Evaluation evaluation =
                evaluate(
                        List.of(
                                entry("svc:core", RiskLevel.HIGH, false),
                                entry("svc:foggy", RiskLevel.UNKNOWN, true)),
                        delta(2, List.of(change("svc:x", RiskLevel.LOW, RiskLevel.MEDIUM)), 1),
                        true);

        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(codes(evaluation))
                .containsExactly(
                        PolicyTaxonomy.EVIDENCE_UNCERTAIN,
                        PolicyTaxonomy.HIGH_RISK_REMAINS,
                        PolicyTaxonomy.RESULT_TRUNCATED,
                        PolicyTaxonomy.RISK_INCREASED,
                        PolicyTaxonomy.UNKNOWN_RISK_PRESENT);
    }

    @Test
    void repeatedEvaluationIsDeterministic() {
        List<RiskResponse.RiskEntry> risks =
                List.of(
                        entry("svc:core", RiskLevel.HIGH, false),
                        entry("svc:foggy", RiskLevel.UNKNOWN, true));
        SimulationDelta delta =
                delta(1, List.of(change("svc:x", RiskLevel.LOW, RiskLevel.MEDIUM)), 1);

        assertThat(evaluate(risks, delta, true)).isEqualTo(evaluate(risks, delta, true));
    }

    @Test
    void nullRisksTreatedAsEmpty() {
        PolicyEvaluator.Evaluation evaluation =
                evaluate(null, delta(0, List.of(), 0), false);

        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.ALLOW);
    }
}
