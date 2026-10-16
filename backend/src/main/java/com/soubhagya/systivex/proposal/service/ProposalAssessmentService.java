package com.soubhagya.systivex.proposal.service;

import com.soubhagya.systivex.intelligence.ImpactAnalysisService;
import com.soubhagya.systivex.intelligence.ImpactQueryValidator;
import com.soubhagya.systivex.intelligence.ProposalConflictException;
import com.soubhagya.systivex.intelligence.RiskResponse;
import com.soubhagya.systivex.intelligence.SimulationOperation;
import com.soubhagya.systivex.intelligence.SimulationProposalValidator;
import com.soubhagya.systivex.intelligence.SimulationResponse;
import com.soubhagya.systivex.intelligence.SimulationService;
import com.soubhagya.systivex.policy.PolicyService;
import com.soubhagya.systivex.policy.PolicyEvaluationResponse;
import com.soubhagya.systivex.policy.PolicyEvaluator;
import com.soubhagya.systivex.proposal.api.ProposalResponse;
import com.soubhagya.systivex.proposal.api.SubmitRequest;
import com.soubhagya.systivex.proposal.model.AuditAction;
import com.soubhagya.systivex.proposal.model.AuditOutcome;
import com.soubhagya.systivex.proposal.model.ProposalRevision;
import com.soubhagya.systivex.proposal.model.ProposalStatus;
import com.soubhagya.systivex.proposal.repository.ProposalRevisionRepository;
import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import com.soubhagya.systivex.twin.repository.SystemRelationshipRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proposal assessment and submission (Phase 11 S4, D29).
 *
 * <p>Assessment loads the twin entity/edge lists once inside a
 * {@code REPEATABLE_READ} transaction and runs validation, simulation,
 * policy, risk, and fingerprinting over those exact in-memory lists —
 * fingerprint and assessment are coherent by construction. Only the
 * stored canonical operations feed the computation; the request carries
 * no assessment, risk, policy, or delta fields to trust. Submission is a
 * guarded transition binding the caller's revision and hashes to the
 * exact stored assessment. Approval, rejection, and expiry sweeps arrive
 * in later slices.
 */
@Service
public class ProposalAssessmentService {

    static final Duration ASSESSMENT_TTL = Duration.ofDays(7);

    private static final ObjectMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private final ProposalRevisionRepository revisions;

    private final AuditService audits;

    private final SystemEntityRepository entities;

    private final SystemRelationshipRepository relationships;

    private final ImpactAnalysisService impactService;

    private final SimulationService simulationService;

    private final PolicyService policyService;

    public ProposalAssessmentService(
            ProposalRevisionRepository revisions,
            AuditService audits,
            SystemEntityRepository entities,
            SystemRelationshipRepository relationships,
            ImpactAnalysisService impactService,
            SimulationService simulationService,
            PolicyService policyService) {
        this.revisions = revisions;
        this.audits = audits;
        this.entities = entities;
        this.relationships = relationships;
        this.impactService = impactService;
        this.simulationService = simulationService;
        this.policyService = policyService;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ProposalResponse assess(UUID proposalKey, String issuer, String subject) {
        requireIdentity(issuer, subject);
        ProposalRevision latest = ProposalLookup.latestOrThrow(revisions, proposalKey);
        if (latest.getStatus() != ProposalStatus.DRAFT) {
            throw new ProposalConflictException(
                    "Only DRAFT revisions can be assessed; current status: " + latest.getStatus());
        }
        if (!ProposalCanonical.sha256Hex(ProposalCanonical.normalize(latest.getOperations()))
                .equals(latest.getOperationsHash())) {
            throw new ProposalConflictException("Stored operations do not match the recorded hash");
        }
        List<SystemEntity> snapshotEntities = entities.findAll();
        List<SystemRelationship> snapshotEdges = relationships.findAll();
        Map<UUID, SystemEntity> entitiesById = new LinkedHashMap<>();
        for (SystemEntity entity : snapshotEntities) {
            entitiesById.put(entity.getId(), entity);
        }
        List<SimulationOperation> operations =
                ProposalCanonical.parseOperations(latest.getOperations());
        SimulationProposalValidator.ResolvedProposal resolved =
                SimulationProposalValidator.validate(
                        operations,
                        Set.of(),
                        ProposalOperations.edgeKeys(snapshotEdges),
                        ProposalOperations.snapshotResolver(entitiesById));
        List<String> rootIds = touchedIds(resolved);
        ImpactQueryValidator.ParsedInputs inputs =
                impactService.parseSnapshotInputs(entitiesById, rootIds, null, null);
        SimulationResponse simulation =
                simulationService.simulateResolved(inputs, snapshotEntities, snapshotEdges, resolved);
        PolicyEvaluationResponse policy = policyService.evaluateResolved(simulation);
        String fingerprint = TwinFingerprint.of(snapshotEntities, snapshotEdges);
        String assessmentJson = assessmentJson(inputs, simulation, policy);
        String assessmentHash = ProposalCanonical.sha256Hex(assessmentJson);

        latest.setAssessment(assessmentJson);
        latest.setAssessmentHash(assessmentHash);
        latest.setPolicyDecision(policy.decision().name());
        latest.setPolicyVersion(policy.policyVersion());
        latest.setRiskTaxonomy(simulation.after().risk().taxonomyVersion());
        latest.setTwinFingerprint(fingerprint);
        latest.setSnapshotObservedAt(simulation.after().risk().snapshotObservedAt());
        latest.setExpiresAt(Instant.now().plus(ASSESSMENT_TTL));
        latest.setStatus(ProposalStatus.ASSESSED);
        try {
            revisions.saveAndFlush(latest);
        } catch (OptimisticLockingFailureException e) {
            throw new ProposalConflictException("Proposal was modified concurrently; refetch and retry");
        }
        audits.append(
                new AuditService.AuditAppend(
                        issuer,
                        subject,
                        AuditAction.ASSESSED,
                        AuditOutcome.SUCCESS,
                        latest.getProposalKey(),
                        latest.getRevisionNumber(),
                        latest.getOperationsHash(),
                        assessmentHash,
                        null,
                        UUID.randomUUID().toString()));
        return ProposalResponse.from(latest);
    }

    @Transactional
    public ProposalResponse submit(UUID proposalKey, SubmitRequest request, String issuer, String subject) {
        requireIdentity(issuer, subject);
        if (request == null) {
            throw new IllegalArgumentException("Submission must bind a revision and hashes");
        }
        ProposalRevision latest = ProposalLookup.latestOrThrow(revisions, proposalKey);
        if (latest.getStatus() != ProposalStatus.ASSESSED) {
            throw new ProposalConflictException(
                    "Only ASSESSED revisions can be submitted; current status: " + latest.getStatus());
        }
        if (request.revisionNumber() != latest.getRevisionNumber()) {
            throw new ProposalConflictException(
                    "Stale revision: request binds revision " + request.revisionNumber()
                            + " but current revision is " + latest.getRevisionNumber());
        }
        if (request.operationsHash() == null
                || !request.operationsHash().equals(latest.getOperationsHash())
                || !ProposalCanonical.sha256Hex(ProposalCanonical.normalize(latest.getOperations()))
                        .equals(latest.getOperationsHash())) {
            throw new ProposalConflictException("Operations hash mismatch: assessment is stale");
        }
        if (request.assessmentHash() == null
                || !request.assessmentHash().equals(latest.getAssessmentHash())
                || latest.getAssessment() == null
                || !ProposalCanonical.sha256Hex(ProposalCanonical.normalize(latest.getAssessment()))
                        .equals(latest.getAssessmentHash())) {
            throw new ProposalConflictException("Assessment hash mismatch: assessment is stale");
        }
        if (latest.getTwinFingerprint() == null
                || latest.getPolicyDecision() == null
                || latest.getSnapshotObservedAt() == null) {
            throw new ProposalConflictException("Assessment evidence is incomplete");
        }
        if (latest.getExpiresAt() == null || !latest.getExpiresAt().isAfter(Instant.now())) {
            throw new ProposalConflictException("Proposal assessment has expired; amend to reassess");
        }
        latest.setStatus(ProposalStatus.AWAITING_APPROVAL);
        try {
            revisions.saveAndFlush(latest);
        } catch (OptimisticLockingFailureException e) {
            throw new ProposalConflictException("Proposal was modified concurrently; refetch and retry");
        }
        audits.append(
                new AuditService.AuditAppend(
                        issuer,
                        subject,
                        AuditAction.SUBMITTED,
                        AuditOutcome.SUCCESS,
                        latest.getProposalKey(),
                        latest.getRevisionNumber(),
                        latest.getOperationsHash(),
                        latest.getAssessmentHash(),
                        null,
                        UUID.randomUUID().toString()));
        return ProposalResponse.from(latest);
    }

    static List<String> touchedIds(SimulationProposalValidator.ResolvedProposal resolved) {
        Set<String> touched = new TreeSet<>();
        for (UUID id : resolved.removedEntityIds()) {
            touched.add(id.toString());
        }
        for (SimulationProposalValidator.ResolvedEdge edge : resolved.addedEdges()) {
            touched.add(edge.sourceId().toString());
            touched.add(edge.targetId().toString());
        }
        for (SimulationProposalValidator.ResolvedEdge edge : resolved.removedEdges()) {
            touched.add(edge.sourceId().toString());
            touched.add(edge.targetId().toString());
        }
        return List.copyOf(touched);
    }

    private static String assessmentJson(
            ImpactQueryValidator.ParsedInputs inputs,
            SimulationResponse simulation,
            PolicyEvaluationResponse policy) {
        List<String> roots = new ArrayList<>();
        for (SystemEntity start : inputs.starts()) {
            roots.add(start.getId().toString());
        }
        List<String> includeTypes = new ArrayList<>();
        inputs.includeTypes().forEach(type -> includeTypes.add(type.name()));
        includeTypes.sort(String::compareTo);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("maxDepth", inputs.maxDepth());
        settings.put("includeTypes", includeTypes);
        settings.put("rootCount", roots.size());
        settings.put("roots", roots);

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("baselineEntityCount", simulation.graph().baselineEntityCount());
        graph.put("hypotheticalEntityCount", simulation.graph().hypotheticalEntityCount());
        graph.put("baselineRelationshipCount", simulation.graph().baselineRelationshipCount());
        graph.put("hypotheticalRelationshipCount", simulation.graph().hypotheticalRelationshipCount());
        graph.put("removedEntityCount", simulation.graph().removedEntityCount());
        graph.put("addedRelationshipCount", simulation.graph().addedRelationshipCount());
        graph.put("removedRelationshipCount", simulation.graph().removedRelationshipCount());

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("beforeImpactedCount", simulation.delta().beforeImpactedCount());
        delta.put("afterImpactedCount", simulation.delta().afterImpactedCount());
        delta.put("impactedAddedCount", simulation.delta().impactedAddedCount());
        delta.put("impactedRemovedCount", simulation.delta().impactedRemovedCount());
        delta.put("beforeRiskCount", simulation.delta().beforeRiskCount());
        delta.put("afterRiskCount", simulation.delta().afterRiskCount());
        delta.put("riskAddedCount", simulation.delta().riskAddedCount());
        delta.put("riskRemovedCount", simulation.delta().riskRemovedCount());
        delta.put("uncertainBeforeCount", simulation.delta().uncertainBeforeCount());
        delta.put("uncertainAfterCount", simulation.delta().uncertainAfterCount());
        delta.put("truncationChanged", simulation.delta().truncationChanged());

        List<Map<String, String>> rules = new ArrayList<>();
        for (PolicyEvaluator.FiredRule rule : policy.rulesFired()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("code", rule.code());
            entry.put("outcome", rule.outcome().name());
            entry.put("detail", rule.detail());
            rules.add(entry);
        }
        Map<String, Object> policySection = new LinkedHashMap<>();
        policySection.put("decision", policy.decision().name());
        policySection.put("policyVersion", policy.policyVersion());
        policySection.put("rulesFired", rules);

        Map<String, String> levels = new TreeMap<>();
        List<String> uncertain = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (RiskResponse.RiskEntry entry : simulation.after().risk().risks()) {
            levels.put(entry.id().toString(), entry.level().name());
            if (entry.uncertain()) {
                uncertain.add(entry.id().toString());
            }
            if (entry.stale()) {
                stale.add(entry.id().toString());
            }
        }
        uncertain.sort(String::compareTo);
        stale.sort(String::compareTo);
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("taxonomyVersion", simulation.after().risk().taxonomyVersion());
        risk.put("snapshotObservedAt", simulation.after().risk().snapshotObservedAt().toString());
        risk.put("levels", levels);
        risk.put("uncertain", uncertain);
        risk.put("stale", stale);

        Map<String, Object> simulationSection = new LinkedHashMap<>();
        simulationSection.put("graph", graph);
        simulationSection.put("delta", delta);
        simulationSection.put("limitations", simulation.limitations());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("settings", settings);
        document.put("simulation", simulationSection);
        document.put("policy", policySection);
        document.put("risk", risk);
        return JSON.writeValueAsString(document);
    }

    private static void requireIdentity(String issuer, String subject) {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Authenticated issuer and subject are required");
        }
    }
}
