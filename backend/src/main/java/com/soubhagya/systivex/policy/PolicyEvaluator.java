package com.soubhagya.systivex.policy;

import com.soubhagya.systivex.intelligence.RiskLevel;
import com.soubhagya.systivex.intelligence.RiskResponse;
import com.soubhagya.systivex.intelligence.SimulationDelta;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure advisory policy evaluator over server-computed simulation results:
 * no repository access, no Spring, no I/O.
 *
 * <p>Every rule from {@link PolicyTaxonomy} is evaluated in stable order
 * and every fired rule is reported (sorted by code); final precedence is
 * {@code DENY > REVIEW > ALLOW}. The evaluator never sees caller-supplied
 * risk or deltas — callers pass the server-computed after-scope risks,
 * the server-computed delta, and the truncation flags of both scopes.
 */
public final class PolicyEvaluator {

    private PolicyEvaluator() {}

    /** One fired rule: stable code, its outcome, and a counted detail. */
    public record FiredRule(String code, PolicyDecision outcome, String detail) {}

    /** A deterministic evaluation: final decision plus every fired rule. */
    public record Evaluation(PolicyDecision decision, List<FiredRule> firedRules) {}

    /**
     * Evaluates the {@code policy-v1} ruleset.
     *
     * @param afterRisks server-computed hypothetical-scope risk entries
     * @param delta server-computed before/after delta
     * @param truncated true when either impact scope truncated
     */
    public static Evaluation evaluate(
            List<RiskResponse.RiskEntry> afterRisks,
            SimulationDelta delta,
            boolean truncated) {
        List<RiskResponse.RiskEntry> risks =
                afterRisks == null ? List.of() : afterRisks;
        List<FiredRule> fired = new ArrayList<>();

        long highCount = countLevel(risks, RiskLevel.HIGH);
        if (highCount > 0) {
            fired.add(
                    new FiredRule(
                            PolicyTaxonomy.HIGH_RISK_REMAINS,
                            PolicyDecision.DENY,
                            highCount
                                    + " hypothetical impacted components carry HIGH risk;"
                                    + " the change leaves high risk standing."));
        }

        if (truncated) {
            fired.add(
                    new FiredRule(
                            PolicyTaxonomy.RESULT_TRUNCATED,
                            PolicyDecision.DENY,
                            "Baseline or hypothetical impact traversal truncated;"
                                    + " the partial result cannot support an affirming outcome."));
        }

        List<String> increases = increasedRefs(delta);
        if (delta.riskAddedCount() > 0 || !increases.isEmpty()) {
            fired.add(
                    new FiredRule(
                            PolicyTaxonomy.RISK_INCREASED,
                            PolicyDecision.REVIEW,
                            describeIncrease(delta, increases)));
        }

        long unknownCount = countLevel(risks, RiskLevel.UNKNOWN);
        if (unknownCount > 0) {
            fired.add(
                    new FiredRule(
                            PolicyTaxonomy.UNKNOWN_RISK_PRESENT,
                            PolicyDecision.REVIEW,
                            unknownCount
                                    + " hypothetical impacted components carry UNKNOWN risk"
                                    + " (missing evidence); missing evidence is not safety."));
        }

        if (delta.uncertainAfterCount() > 0) {
            fired.add(
                    new FiredRule(
                            PolicyTaxonomy.EVIDENCE_UNCERTAIN,
                            PolicyDecision.REVIEW,
                            delta.uncertainAfterCount()
                                    + " hypothetical impacted components carry uncertain"
                                    + " (missing or stale) runtime evidence."));
        }

        fired.sort(Comparator.comparing(FiredRule::code));

        PolicyDecision decision = PolicyDecision.ALLOW;
        for (FiredRule rule : fired) {
            if (rule.outcome() == PolicyDecision.DENY) {
                decision = PolicyDecision.DENY;
                break;
            }
            if (rule.outcome() == PolicyDecision.REVIEW) {
                decision = PolicyDecision.REVIEW;
            }
        }
        return new Evaluation(decision, List.copyOf(fired));
    }

    private static long countLevel(List<RiskResponse.RiskEntry> risks, RiskLevel level) {
        return risks.stream().filter(e -> e.level() == level).count();
    }

    /**
     * Refs whose level moved upward, against the real
     * {@link SimulationDelta} contract: a change counts as an increase
     * when the after-level outranks the before-level ({@code LOW <
     * MEDIUM < HIGH}) or when the after-level is {@code HIGH} arrived at
     * from any other level. Moves into or out of {@code UNKNOWN} that do
     * not reach {@code HIGH} are uncertainty, not increase.
     */
    static List<String> increasedRefs(SimulationDelta delta) {
        List<String> refs = new ArrayList<>();
        for (SimulationDelta.RiskLevelChange change : delta.riskLevelChanges()) {
            if (isIncrease(change.before(), change.after())) {
                refs.add(change.entityRef());
            }
        }
        refs.sort(String::compareTo);
        return refs;
    }

    static boolean isIncrease(RiskLevel before, RiskLevel after) {
        if (after == RiskLevel.HIGH && before != RiskLevel.HIGH) {
            return true;
        }
        return rank(before) >= 0 && rank(after) >= 0 && rank(after) > rank(before);
    }

    private static int rank(RiskLevel level) {
        return switch (level) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case UNKNOWN -> -1;
        };
    }

    private static String describeIncrease(
            SimulationDelta delta, List<String> increases) {
        StringBuilder detail = new StringBuilder("Hypothetical risk rises: ");
        boolean needJoin = false;
        if (delta.riskAddedCount() > 0) {
            detail.append(delta.riskAddedCount()).append(" newly impacted risks appear");
            needJoin = true;
        }
        if (!increases.isEmpty()) {
            if (needJoin) {
                detail.append("; ");
            }
            detail.append("upward level changes at ").append(String.join(", ", increases));
        }
        detail.append(".");
        return detail.toString();
    }
}
