package com.soubhagya.systivex.policy;

import com.soubhagya.systivex.intelligence.SimulationRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Advisory policy-evaluation endpoint over the persisted twin graph.
 *
 * <p>The request body is the exact Phase 8 simulation proposal wire
 * format ({@link SimulationRequest} — same fields, same validation, no
 * alternate names or extra aliases). Caller-supplied risk or delta
 * content is never accepted: the service re-runs the simulation
 * server-side and evaluates the computed result inside one read-only
 * transaction (see {@link PolicyService}). Shape failures fail 400,
 * unknown identifiers 404, proposal conflicts 409 via the existing
 * handler. Every response is advisory only — it authorizes, approves,
 * executes, and persists nothing.
 */
@RestController
@RequestMapping("/api/v1/twin")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping("/policy-evaluations")
    public PolicyEvaluationResponse evaluate(@RequestBody SimulationRequest request) {
        return policyService.evaluate(request);
    }
}
