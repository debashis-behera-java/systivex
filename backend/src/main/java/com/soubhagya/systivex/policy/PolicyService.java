package com.soubhagya.systivex.policy;

import com.soubhagya.systivex.intelligence.SimulationRequest;
import com.soubhagya.systivex.intelligence.SimulationResponse;
import com.soubhagya.systivex.intelligence.SimulationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 9 advisory policy evaluation: runs the caller's change proposal
 * through the existing simulation orchestration, then classifies the
 * server-computed hypothetical result against the immutable
 * {@code policy-v1} ruleset.
 *
 * <p>Trust boundary: caller-supplied risk assessments and deltas are never
 * accepted — only the proposal (validated with the exact simulation
 * contract: 400 shape, 404 resolution, 409 conflicts) is taken from the
 * request, and every evaluated fact is recomputed server-side by
 * {@link SimulationService} reusing the existing traversal and risk
 * classification. One {@code readOnly} transaction: no proposals,
 * decisions, approvals, executions, or audit records are persisted.
 */
@Service
public class PolicyService {

    static final String ADVISORY = "ADVISORY POLICY EVALUATION — NOT AN AUTHORIZATION; NOT EXECUTED";

    static final String NOTE =
            "Advisory policy outcome (taxonomy policy-v1) over a simulated,"
                    + " unexecuted change: ALLOW means no configured rule required"
                    + " intervention, REVIEW means a configured condition needs a"
                    + " human look, DENY means a configured hard rule fired against"
                    + " the hypothetical result. This evaluation authorizes nothing,"
                    + " approves nothing, executes nothing, and persists nothing;"
                    + " verify against the twin and live backend state before acting.";

    private final SimulationService simulationService;

    public PolicyService(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    /**
     * Validates the proposal with simulation semantics, evaluates the
     * server-computed hypothetical result, and returns the advisory
     * decision. Blank/oversized input fails 400, unknown identifiers 404,
     * proposal conflicts 409 — via the existing handler.
     */
    @Transactional(readOnly = true)
    public PolicyEvaluationResponse evaluate(SimulationRequest request) {
        return evaluateResolved(simulationService.simulate(request));
    }

    /**
     * Classifies an already-computed simulation against the immutable
     * {@code policy-v1} ruleset. Public so governed assessment evaluates
     * the simulation it captured over its own snapshot.
     */
    @Transactional(readOnly = true)
    public PolicyEvaluationResponse evaluateResolved(SimulationResponse simulation) {
        PolicyEvaluator.Evaluation evaluation =
                PolicyEvaluator.evaluate(
                        simulation.after().risk().risks(),
                        simulation.delta(),
                        simulation.after().impact().truncated()
                                || simulation.before().impact().truncated());

        return new PolicyEvaluationResponse(
                evaluation.decision(),
                PolicyTaxonomy.VERSION,
                ADVISORY,
                simulation.settings(),
                evaluation.firedRules(),
                new PolicyEvaluationResponse.SimulationSummary(
                        simulation.graph(), simulation.delta(), simulation.limitations()),
                NOTE);
    }
}
