package com.soubhagya.systivex.policy;

import com.soubhagya.systivex.intelligence.SimulationDelta;
import com.soubhagya.systivex.intelligence.SimulationGraphSummary;
import com.soubhagya.systivex.intelligence.SimulationSettings;
import java.util.List;

/**
 * Read shape of one advisory policy evaluation.
 *
 * <p>{@code decision} is an advisory outcome over the server-computed
 * hypothetical result — never an authorization grant and never an
 * execution permission. {@code rulesFired} reports every fired
 * {@code policy-v1} rule (sorted by code) with stable reason codes.
 * {@code simulation} is a bounded summary (graph counts, deterministic
 * delta, limitations) rather than the full before/after scopes.
 * {@code advisory} and {@code note} state unmistakably that nothing was
 * authorized, approved, executed, or persisted.
 */
public record PolicyEvaluationResponse(
        PolicyDecision decision,
        String policyVersion,
        String advisory,
        SimulationSettings settings,
        List<PolicyEvaluator.FiredRule> rulesFired,
        SimulationSummary simulation,
        String note) {

    /** Bounded server-computed evidence behind the decision. */
    public record SimulationSummary(
            SimulationGraphSummary graph,
            SimulationDelta delta,
            List<String> limitations) {}
}
