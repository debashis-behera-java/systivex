package com.soubhagya.systivex.intelligence;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import com.soubhagya.systivex.twin.repository.SystemRelationshipRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 8 deterministic change simulation: compares the existing twin's
 * impact and risk with a hypothetical graph produced by a validated change
 * proposal — without persisting anything.
 *
 * <p>Reuse, not duplication: root/maxDepth/includeTypes validation and the
 * BFS traversal stay inside {@link ImpactAnalysisService} (identical
 * 400/404 semantics and settings for both scopes), and risk classification
 * stays inside {@link RiskService}. Proposal validation is the pure
 * {@link SimulationProposalValidator}, hypothetical application the pure
 * {@link HypotheticalGraph}, and before/after comparison the pure
 * {@link SimulationDiff}. This service only orchestrates, inside one
 * {@code readOnly} transaction: no entities, relationships, proposals, or
 * runtime metadata are written.
 */
@Service
public class SimulationService {

    static final String ADVISORY = "SIMULATED — NOT EXECUTED";

    private final ImpactAnalysisService impactService;
    private final RiskService riskService;
    private final SystemEntityRepository entities;
    private final SystemRelationshipRepository relationships;

    public SimulationService(
            ImpactAnalysisService impactService,
            RiskService riskService,
            SystemEntityRepository entities,
            SystemRelationshipRepository relationships) {
        this.impactService = impactService;
        this.riskService = riskService;
        this.entities = entities;
        this.relationships = relationships;
    }

    /**
     * Validates the request and proposal, then compares baseline versus
     * hypothetical impact and risk over identical roots and settings.
     * Blank/oversized input fails 400, unknown identifiers 404, proposal
     * conflicts 409 — via the existing handler.
     */
    @Transactional(readOnly = true)
    public SimulationResponse simulate(SimulationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Simulation request body is required");
        }
        ImpactAnalysisService.ParsedInputs inputs =
                impactService.parseInputs(
                        request.from(), request.maxDepth(), request.includeTypes());
        List<SystemEntity> starts = inputs.starts();
        Set<UUID> rootIds = new HashSet<>();
        for (SystemEntity start : starts) {
            rootIds.add(start.getId());
        }

        List<SystemEntity> baselineEntities = entities.findAll();
        List<SystemRelationship> baselineEdges = relationships.findAll();

        SimulationProposalValidator.ResolvedProposal proposal =
                SimulationProposalValidator.validate(
                        request.operations(),
                        rootIds,
                        existingEdgeKeys(baselineEdges),
                        raw -> impactService.resolveEntity(raw.trim()).getId());

        ImpactResponse beforeImpact =
                impactService.analyzeResolved(
                        starts,
                        inputs.maxDepth(),
                        inputs.includeTypes(),
                        baselineEntities,
                        baselineEdges);
        RiskResponse beforeRisk =
                riskService.assessResolved(
                        starts,
                        inputs.maxDepth(),
                        inputs.includeTypes(),
                        baselineEntities,
                        baselineEdges);

        Map<UUID, SystemEntity> entityById = new HashMap<>();
        for (SystemEntity entity : baselineEntities) {
            entityById.put(entity.getId(), entity);
        }
        HypotheticalGraph.Built hypothetical =
                HypotheticalGraph.build(baselineEntities, baselineEdges, proposal, entityById);

        ImpactResponse afterImpact =
                impactService.analyzeResolved(
                        starts,
                        inputs.maxDepth(),
                        inputs.includeTypes(),
                        hypothetical.entities(),
                        hypothetical.edges());
        RiskResponse afterRisk =
                riskService.assessResolved(
                        starts,
                        inputs.maxDepth(),
                        inputs.includeTypes(),
                        hypothetical.entities(),
                        hypothetical.edges());

        SimulationDelta delta =
                SimulationDiff.diff(beforeImpact, afterImpact, beforeRisk, afterRisk);
        List<String> limitations =
                SimulationDiff.limitations(
                        ADVISORY, beforeImpact, afterImpact, beforeRisk, afterRisk);

        SimulationGraphSummary graph =
                new SimulationGraphSummary(
                        baselineEntities.size(),
                        hypothetical.entities().size(),
                        baselineEdges.size(),
                        hypothetical.edges().size(),
                        hypothetical.removedEntityCount(),
                        hypothetical.addedRelationshipCount(),
                        hypothetical.removedRelationshipCount());

        return new SimulationResponse(
                true,
                ADVISORY,
                new SimulationSettings(
                        inputs.maxDepth(),
                        inputs.includeTypes(),
                        starts.size()),
                new SimulationResponse.Scope(beforeImpact, beforeRisk),
                new SimulationResponse.Scope(afterImpact, afterRisk),
                graph,
                delta,
                limitations);
    }

    private static Set<String> existingEdgeKeys(List<SystemRelationship> edges) {
        Set<String> keys = new HashSet<>();
        for (SystemRelationship edge : edges) {
            keys.add(
                    edge.getSource().getId()
                            + "|"
                            + edge.getTarget().getId()
                            + "|"
                            + edge.getRelationshipType().name());
        }
        return keys;
    }
}
