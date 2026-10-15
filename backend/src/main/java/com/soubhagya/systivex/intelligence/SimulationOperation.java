package com.soubhagya.systivex.intelligence;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.soubhagya.systivex.twin.model.SystemRelationshipType;

/**
 * One change-proposal operation, applied to the hypothetical graph only.
 *
 * <p>Exactly three operation types are supported: {@code REMOVE_ENTITY}
 * (requires {@code entity}; the entity and all its incident relationships
 * disappear from the hypothetical graph), {@code ADD_RELATIONSHIP} and
 * {@code REMOVE_RELATIONSHIP} (both require {@code source}, {@code target},
 * and {@code relationshipType}). Every identifier is a UUID or an external
 * reference, resolved with the exact semantics of the impact query. Shape
 * violations fail 400, unknown identifiers 404, proposal conflicts 409.
 */
public record SimulationOperation(
        @JsonProperty("type") SimulationOperationType type,
        @JsonProperty("entity") String entity,
        @JsonProperty("source") String source,
        @JsonProperty("target") String target,
        @JsonProperty("relationshipType")
        @JsonAlias("relationship_type")
        SystemRelationshipType relationshipType) {}
