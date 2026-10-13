package com.soubhagya.systivex.intelligence;

import java.time.Duration;
import java.util.Set;

/**
 * Versioned risk taxonomy for Phase 7 deterministic change-risk
 * assessment (version {@code risk-v1}).
 *
 * <h2>Signals (all already persisted — no new evidence is invented)</h2>
 * <ul>
 *   <li><b>Depth</b> from the Phase 5 impact traversal: depth 1
 *       (directly connected) starts at {@code MEDIUM}; depth 2+ starts at
 *       {@code LOW} as transitive reachability.
   *   <li><b>Fan-out</b>: the number of independent evidence paths reaching
   *       the entity. Five or more paths raise the level one step (breadth
   *       of exposure), never above {@code HIGH}.
 *   <li><b>Service health</b> from Phase 6 {@code runtime} metadata: a
 *       reported {@code DOWN} or {@code OUT_OF_SERVICE} status sets
 *       {@code HIGH} directly (an unhealthy dependency of a change is a
 *       severe signal regardless of depth).
 *   <li><b>Cumulative failure counters</b> from Phase 6 {@code runtime}
 *       metadata (families ending in {@code .failure}, {@code .rejected},
 *       or {@code .declined} with a value above zero): raise the level one
 *       step. Counters are cumulative lifetime evidence — they are never
 *       divided into rates and never presented as proof of an ongoing
 *       incident. Zero counters add no reason and never lower a level: zero
 *       does not establish safety.
   *   <li><b>Evidence availability and freshness</b>: a {@code SERVICE}
   *       entity without {@code runtime} metadata is {@code UNKNOWN} (the
   *       evidence needed to judge is missing). Metadata older than
   *       {@link #STALE_AFTER} — or dated in the future, which can only
   *       mean clock skew or tampered metadata — is stale: severe signals
   *       computed from it are retained (staleness never downgrades
   *       concern) while the entry is flagged {@code stale} and
   *       {@code uncertain}.
 * </ul>
 *
 * <h2>Grounding limits (documented, not hidden)</h2>
 * <ul>
 *   <li>Only {@code SERVICE} entities carry {@code runtime} metadata, so
 *       only services receive health/counter/freshness reasoning. Tables,
 *       databases, and APIs are assessed structurally (depth + fan-out);
 *       the response note states this boundary.
 *   <li>Success counters never lower a level. Absent counter families are
 *       absent evidence, not zero evidence.
 *   <li>Every assessment is advisory: potentially affected does not mean
 *       confirmed breakage, and a result never authorizes an action.
 * </ul>
 */
public final class RiskTaxonomy {

    static final String VERSION = "risk-v1";

    /**
     * Freshness boundary: runtime evidence older than this at assessment
     * time is stale. Boundary-tested: exactly this age is still fresh;
     * anything older is stale.
     */
    static final Duration STALE_AFTER = Duration.ofMinutes(30);

    /** Counter-family suffixes treated as cumulative failure evidence. */
    static final Set<String> FAILURE_FAMILY_SUFFIXES =
            Set.of(".failure", ".rejected", ".declined");

    /**
     * Evidence paths reaching one entity that count as broad exposure.
     * Calibrated at five so routine triangle/diamond alternatives (three
     * to four paths on the Phase 4 graph, terminated silently by the
     * impact traversal but still recorded as alternative evidence) do not
     * inflate routine reachability, while genuinely broad exposure does.
     */
    static final int BROAD_EXPOSURE_PATHS = 5;

    private RiskTaxonomy() {}
}
