package com.soubhagya.systivex.policy;

/**
 * Advisory outcome of one deterministic policy evaluation (Phase 9).
 *
 * <p>These are advisory policy outcomes over a simulated, unexecuted
 * change — never execution permissions and never authorization grants.
 * {@code DENY} means a configured hard rule fired against the
 * hypothetical result; {@code REVIEW} means a configured condition
 * needs a human look; {@code ALLOW} means no configured rule required
 * intervention (still advisory, still not authorization).
 */
public enum PolicyDecision {
    ALLOW,
    REVIEW,
    DENY
}
