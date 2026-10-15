package com.soubhagya.systivex.intelligence;

import java.util.List;

/**
 * Read shape of one deterministic change simulation.
 *
 * <p>{@code before} and {@code after} are the baseline and hypothetical
 * scopes, each pairing the reused impact traversal with the reused risk
 * classification over identical roots and settings. {@code delta} is the
 * deterministic before/after difference, {@code graph} summarises the
 * baseline versus hypothetical working sets, and {@code limitations} states
 * truncation, uncertainty, and the unexecuted nature of the result.
 * {@code simulated} is always true and {@code advisory} always carries the
 * unmistakable {@code SIMULATED — NOT EXECUTED} marker: nothing was
 * persisted and nothing was executed.
 */
public record SimulationResponse(
        boolean simulated,
        String advisory,
        SimulationSettings settings,
        Scope before,
        Scope after,
        SimulationGraphSummary graph,
        SimulationDelta delta,
        List<String> limitations) {

    /** One scope of the comparison: impact traversal plus risk assessment. */
    public record Scope(ImpactResponse impact, RiskResponse risk) {}
}
