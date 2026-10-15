package com.soubhagya.systivex.intelligence;

/**
 * Baseline-versus-hypothetical working-set summary.
 *
 * <p>{@code removedRelationshipCount} counts every baseline edge absent
 * from the hypothetical graph: explicitly removed edges plus edges
 * cascaded away with a removed entity. All counts are derived from
 * in-memory working copies; the persisted graph is untouched.
 */
public record SimulationGraphSummary(
        int baselineEntityCount,
        int hypotheticalEntityCount,
        int baselineRelationshipCount,
        int hypotheticalRelationshipCount,
        int removedEntityCount,
        int addedRelationshipCount,
        int removedRelationshipCount) {}
