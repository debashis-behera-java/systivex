package com.soubhagya.systivex.intelligence;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import com.soubhagya.systivex.twin.repository.SystemRelationshipRepository;
import com.soubhagya.systivex.twin.service.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic change-impact analysis: read-only BFS over the persisted
 * twin graph (Phase 5, D23).
 *
 * <p>Orchestration facade only. Request validation lives in
 * {@link ImpactQueryValidator} (shared by the live query, simulation, and
 * governed assessment), the pure traversal in {@link ImpactTraversal},
 * and evidence sentences in {@link ImpactExplanation}. Stored directions
 * are respected in both forward and reverse traversal; downstream
 * dependencies are structural reachability, never confirmed breakage.
 *
 * <p>Read-only: one {@code readOnly} transaction, no writes, no migration,
 * no filesystem or network access. Oversized input fails before any graph
 * work (T2: validated, size-bounded, fail-closed).
 */
@Service
public class ImpactAnalysisService {

    private final SystemEntityRepository entities;
    private final SystemRelationshipRepository relationships;

    public ImpactAnalysisService(
            SystemEntityRepository entities, SystemRelationshipRepository relationships) {
        this.entities = entities;
        this.relationships = relationships;
    }

    /**
     * Validates raw query input, resolves every starting entity, and runs the
     * analysis in one read-only transaction. Blank/oversized input fails with
     * {@link IllegalArgumentException} (400); unknown identifiers fail with
     * {@link EntityNotFoundException} (404).
     */
    @Transactional(readOnly = true)
    public ImpactResponse analyze(
            List<String> rawFrom, Integer rawMaxDepth, List<String> rawIncludeTypes) {
        ImpactQueryValidator.ParsedInputs inputs = parseInputs(rawFrom, rawMaxDepth, rawIncludeTypes);
        List<SystemEntity> allEntities = entities.findAll();
        List<SystemRelationship> allRelationships = relationships.findAll();
        return analyzeResolved(
                inputs.starts(), inputs.maxDepth(), inputs.includeTypes(),
                allEntities, allRelationships);
    }

    /**
     * Validates raw query input with impact-query semantics (blank/oversized
     * input fails 400, unknown identifiers 404) and returns resolved inputs.
     * Public so simulation reuses the exact validation without duplicating
     * the semantics.
     */
    public ImpactQueryValidator.ParsedInputs parseInputs(
            List<String> rawFrom, Integer rawMaxDepth, List<String> rawIncludeTypes) {
        return ImpactQueryValidator.parseInputs(rawFrom, rawMaxDepth, rawIncludeTypes, this::resolve);
    }

    /**
     * Snapshot-consistent variant: identical validation, but identifiers
     * resolve against the caller-supplied in-memory snapshot only. Public so
     * governed assessment runs simulation, risk, policy, and fingerprinting
     * over one captured state.
     */
    public ImpactQueryValidator.ParsedInputs parseSnapshotInputs(
            Map<UUID, SystemEntity> entitiesById,
            List<String> rawFrom,
            Integer rawMaxDepth,
            List<String> rawIncludeTypes) {
        return ImpactQueryValidator.parseSnapshotInputs(
                entitiesById, rawFrom, rawMaxDepth, rawIncludeTypes);
    }

    /**
     * Runs the deterministic BFS core over caller-supplied entity and edge
     * collections (baseline snapshot or hypothetical simulation working
     * sets). Package-private so simulation reuses the traversal without
     * duplicating it; callers must have validated and resolved inputs first.
     */
    ImpactResponse analyzeResolved(
            List<SystemEntity> starts,
            int maxDepth,
            Set<SystemEntityType> includeTypes,
            List<SystemEntity> allEntities,
            List<SystemRelationship> allRelationships) {
        return ImpactTraversal.analyzeGraph(
                starts,
                allEntities,
                allRelationships,
                maxDepth,
                includeTypes,
                ImpactTraversal.defaultLimits());
    }

    /**
     * Resolves one UUID-or-external-reference identifier with the exact
     * semantics of the impact query (404 on unknown). Package-private so
     * simulation resolves proposal references identically.
     */
    SystemEntity resolveEntity(String raw) {
        return resolve(raw);
    }

    private SystemEntity resolve(String raw) {
        UUID id = tryParseUuid(raw);
        if (id != null) {
            SystemEntity byId = entities.findById(id).orElse(null);
            if (byId != null) {
                return byId;
            }
            // A UUID-shaped string may still be an external reference.
            SystemEntity byRef = entities.findByExternalRef(raw).orElse(null);
            if (byRef != null) {
                return byRef;
            }
            throw new EntityNotFoundException("SystemEntity not found: " + raw);
        }
        return entities
                .findByExternalRef(raw)
                .orElseThrow(() -> new EntityNotFoundException("SystemEntity not found: " + raw));
    }

    private static UUID tryParseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
