package com.soubhagya.systivex.policy;

/**
 * Versioned advisory policy taxonomy for Phase 9 deterministic policy
 * evaluation (version {@code policy-v1}).
 *
 * <h2>Rules (immutable, code-defined, evaluated in stable order)</h2>
 * <ul>
 *   <li><b>HIGH_RISK_REMAINS</b> (advisory DENY): any {@code HIGH} risk
 *       entry remains in the hypothetical after-scope. A change that
 *       leaves high risk standing is not affirmable.</li>
 *   <li><b>RESULT_TRUNCATED</b> (advisory DENY): either the baseline or
 *       the hypothetical impact traversal truncated. A partial result
 *       cannot support an affirming outcome, so evaluation fails closed.
 *   </li>
 *   <li><b>RISK_INCREASED</b> (REVIEW): risk rises in the hypothetical
 *       scope — newly impacted risks appear
 *       ({@code riskAddedCount > 0}) or an entry's level moves upward
 *       ({@code LOW < MEDIUM < HIGH}, or any arrival at {@code HIGH}).
 *       Moves into or out of {@code UNKNOWN} that do not reach
 *       {@code HIGH} are uncertainty, not increase (covered below).</li>
 *   <li><b>UNKNOWN_RISK_PRESENT</b> (REVIEW): any {@code UNKNOWN} risk
 *       entry remains in the after-scope. Missing evidence must raise
 *       caution, never read as safe.</li>
 *   <li><b>EVIDENCE_UNCERTAIN</b> (REVIEW): any after-scope risk entry
 *       carries the {@code uncertain} flag (missing or stale runtime
 *       evidence, per the {@code risk-v1} taxonomy). Stale evidence is
 *       never treated as current.</li>
 * </ul>
 *
 * <h2>Precedence and grounding limits</h2>
 * <ul>
 *   <li>Every rule is evaluated; every fired rule is reported. Final
 *       precedence is {@code DENY > REVIEW > ALLOW}.</li>
 *   <li>An {@code ALLOW} outcome is still advisory and is never an
 *       authorization grant. Missing evidence, exceptions, invalid
 *       proposals, and incomplete analysis never silently produce
 *       {@code ALLOW} — they produce {@code REVIEW}/{@code DENY} or fail
 *       with the existing 400/404/409 semantics before evaluation.</li>
 *   <li>The taxonomy version travels in every response so later policy
 *       work can pin exactly what was evaluated.</li>
 * </ul>
 */
public final class PolicyTaxonomy {

    static final String VERSION = "policy-v1";

    static final String HIGH_RISK_REMAINS = "HIGH_RISK_REMAINS";
    static final String RESULT_TRUNCATED = "RESULT_TRUNCATED";
    static final String RISK_INCREASED = "RISK_INCREASED";
    static final String UNKNOWN_RISK_PRESENT = "UNKNOWN_RISK_PRESENT";
    static final String EVIDENCE_UNCERTAIN = "EVIDENCE_UNCERTAIN";

    private PolicyTaxonomy() {}
}
