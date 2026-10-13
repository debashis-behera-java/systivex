package com.soubhagya.systivex.intelligence;

/**
 * Ordinal risk classification for one impacted twin entity (Phase 7,
 * taxonomy {@code risk-v1} — see {@link RiskTaxonomy}).
 *
 * <p>Levels are advisory orderings, never probabilities and never
 * authorization: {@code HIGH} means several concern signals coincide,
 * {@code UNKNOWN} means the evidence needed to judge is missing or stale
 * (which must raise caution, never lower it). Sorted reasons and explicit
 * uncertainty flags accompany every level.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN
}
