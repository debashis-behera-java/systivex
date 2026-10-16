package com.soubhagya.systivex.proposal.service;

import com.soubhagya.systivex.intelligence.ProposalConflictException;
import com.soubhagya.systivex.intelligence.ProposalExpiredException;
import com.soubhagya.systivex.intelligence.RiskLevel;
import com.soubhagya.systivex.intelligence.RiskResponse;
import com.soubhagya.systivex.intelligence.RiskService;
import com.soubhagya.systivex.proposal.api.ProposalResponse;
import com.soubhagya.systivex.proposal.model.AuditAction;
import com.soubhagya.systivex.proposal.model.AuditOutcome;
import com.soubhagya.systivex.proposal.model.ProposalRevision;
import com.soubhagya.systivex.proposal.model.ProposalStatus;
import com.soubhagya.systivex.proposal.repository.ProposalRevisionRepository;
import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import com.soubhagya.systivex.twin.repository.SystemRelationshipRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Governed approval and rejection (Phase 11 S5, D29).
 *
 * <p>Only {@code AWAITING_APPROVAL} revisions can be decided; the decision
 * and its audit event commit in one transaction, so an audit failure rolls
 * the decision back. Separation of duties is a hard 403 on the
 * {@code (issuer, subject)} pair — the proposer can never decide their own
 * revision, even holding both roles. Approval additionally requires a
 * fresh, non-{@code DENY} assessment: the stored hashes are re-verified,
 * lazy expiry (7-day default, 30-day hard maximum) is enforced, the
 * structural fingerprint is recomputed over one {@code REPEATABLE_READ}
 * snapshot, and risk is reclassified over current rows for the stored
 * roots/settings — any escalation, new {@code UNKNOWN}, or new staleness
 * rejects the approval (409) instead of silently refreshing it. A stale
 * proposal must be amended, reassessed, and resubmitted. {@code APPROVED}
 * is a terminal record: it authorizes nothing by itself and executes
 * nothing — acting on it stays a human step outside the system.
 */
@Service
public class ProposalDecisionService {

    private final ProposalRevisionRepository revisions;

    private final AuditService audits;

    private final SystemEntityRepository entities;

    private final SystemRelationshipRepository relationships;

    private final RiskService riskService;

    public ProposalDecisionService(
            ProposalRevisionRepository revisions,
            AuditService audits,
            SystemEntityRepository entities,
            SystemRelationshipRepository relationships,
            RiskService riskService) {
        this.revisions = revisions;
        this.audits = audits;
        this.entities = entities;
        this.relationships = relationships;
        this.riskService = riskService;
    }

    /**
     * Lazy expiration commits: the {@code EXPIRED} state and its audit
     * event persist while the decision itself is refused with 409, so the
     * expiry failure is excluded from rollback.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ,
            noRollbackFor = ProposalExpiredException.class)
    public ProposalResponse approve(UUID proposalKey, String reason, String issuer, String subject) {
        return decide(proposalKey, reason, issuer, subject, ProposalStatus.APPROVED,
                AuditAction.APPROVED, true);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ,
            noRollbackFor = ProposalExpiredException.class)
    public ProposalResponse reject(UUID proposalKey, String reason, String issuer, String subject) {
        return decide(proposalKey, reason, issuer, subject, ProposalStatus.REJECTED,
                AuditAction.REJECTED, false);
    }

    private ProposalResponse decide(
            UUID proposalKey,
            String reason,
            String issuer,
            String subject,
            ProposalStatus target,
            AuditAction action,
            boolean recheckFreshness) {
        requireIdentity(issuer, subject);
        String decisionReason = requireReason(reason);
        ProposalRevision latest = ProposalLookup.latestOrThrow(revisions, proposalKey);
        if (issuer.equals(latest.getProposerIssuer())
                && subject.equals(latest.getProposerSubject())) {
            throw new AccessDeniedException("The proposer cannot decide their own proposal");
        }
        if (latest.getStatus() != ProposalStatus.AWAITING_APPROVAL) {
            throw new ProposalConflictException(
                    "Only AWAITING_APPROVAL revisions can be decided; current status: "
                            + latest.getStatus());
        }
        if (!ProposalCanonical.sha256Hex(ProposalCanonical.normalize(latest.getOperations()))
                .equals(latest.getOperationsHash())) {
            throw new ProposalConflictException("Stored operations do not match the recorded hash");
        }
        if (latest.getAssessment() == null
                || !ProposalCanonical.sha256Hex(ProposalCanonical.normalize(latest.getAssessment()))
                        .equals(latest.getAssessmentHash())) {
            throw new ProposalConflictException("Stored assessment does not match the recorded hash");
        }
        if (latest.getTwinFingerprint() == null
                || latest.getPolicyDecision() == null
                || latest.getPolicyVersion() == null
                || latest.getRiskTaxonomy() == null
                || latest.getSnapshotObservedAt() == null
                || latest.getExpiresAt() == null) {
            throw new ProposalConflictException("Assessment evidence is incomplete");
        }
        Instant effectiveExpiry = ProposalExpiry.effectiveExpiry(latest);
        if (!effectiveExpiry.isAfter(Instant.now())) {
            ProposalExpiry.markExpired(revisions, audits, latest, issuer, subject);
            throw new ProposalExpiredException(
                    "Proposal assessment has expired; amend to reassess");
        }
        if (recheckFreshness) {
            if ("DENY".equals(latest.getPolicyDecision())) {
                throw new ProposalConflictException(
                        "Policy DENY cannot be approved; reject or amend the proposal");
            }
            List<SystemEntity> snapshotEntities = entities.findAll();
            List<SystemRelationship> snapshotEdges = relationships.findAll();
            if (!TwinFingerprint.of(snapshotEntities, snapshotEdges)
                    .equals(latest.getTwinFingerprint())) {
                throw new ProposalConflictException(
                        "Twin structure changed since assessment; amend to reassess and resubmit");
            }
            recheckRisk(latest, snapshotEntities, snapshotEdges);
        }
        latest.setStatus(target);
        latest.setApproverIssuer(issuer);
        latest.setApproverSubject(subject);
        latest.setReason(decisionReason);
        try {
            revisions.saveAndFlush(latest);
        } catch (ConcurrencyFailureException e) {
            throw new ProposalConflictException("Proposal was modified concurrently; refetch and retry");
        }
        audits.append(
                new AuditService.AuditAppend(
                        issuer,
                        subject,
                        action,
                        AuditOutcome.SUCCESS,
                        latest.getProposalKey(),
                        latest.getRevisionNumber(),
                        latest.getOperationsHash(),
                        latest.getAssessmentHash(),
                        decisionReason,
                        UUID.randomUUID().toString()));
        return ProposalResponse.from(latest);
    }

    private void recheckRisk(
            ProposalRevision latest,
            List<SystemEntity> snapshotEntities,
            List<SystemRelationship> snapshotEdges) {
        StoredRisk stored = StoredRisk.parse(latest.getAssessment());
        Map<UUID, SystemEntity> entitiesById = new LinkedHashMap<>();
        for (SystemEntity entity : snapshotEntities) {
            entitiesById.put(entity.getId(), entity);
        }
        List<SystemEntity> starts = new ArrayList<>();
        for (String root : stored.roots) {
            UUID id;
            try {
                id = UUID.fromString(root);
            } catch (IllegalArgumentException e) {
                throw new ProposalConflictException(
                        "Stored assessment roots are unreadable: assessment is stale");
            }
            SystemEntity start = entitiesById.get(id);
            if (start == null) {
                throw new ProposalConflictException(
                        "Assessment roots no longer resolve: assessment is stale");
            }
            starts.add(start);
        }
        Set<SystemEntityType> includeTypes = EnumSet.noneOf(SystemEntityType.class);
        for (String raw : stored.includeTypes) {
            try {
                includeTypes.add(SystemEntityType.valueOf(raw));
            } catch (IllegalArgumentException e) {
                throw new ProposalConflictException(
                        "Stored assessment settings are unreadable: assessment is stale");
            }
        }
        RiskResponse fresh =
                riskService.assessResolved(
                        starts, stored.maxDepth, includeTypes, snapshotEntities, snapshotEdges);
        for (RiskResponse.RiskEntry entry : fresh.risks()) {
            String id = entry.id().toString();
            String was = stored.levels.get(id);
            if (was == null) {
                throw new ProposalConflictException(
                        "Risk scope changed since assessment; amend to reassess and resubmit");
            }
            if (entry.level() == RiskLevel.UNKNOWN && !"UNKNOWN".equals(was)) {
                throw new ProposalConflictException(
                        "Risk evidence newly UNKNOWN since assessment; amend to reassess");
            }
            RiskLevel previous;
            try {
                previous = RiskLevel.valueOf(was);
            } catch (IllegalArgumentException e) {
                throw new ProposalConflictException(
                        "Stored risk levels are unreadable: assessment is stale");
            }
            if (previous == RiskLevel.UNKNOWN && entry.level() == RiskLevel.HIGH) {
                throw new ProposalConflictException(
                        "Risk escalated to HIGH since assessment; amend to reassess");
            }
            if (previous != RiskLevel.UNKNOWN
                    && entry.level() != RiskLevel.UNKNOWN
                    && rank(entry.level()) > rank(previous)) {
                throw new ProposalConflictException(
                        "Risk level increased since assessment; amend to reassess");
            }
            if (entry.stale() && !stored.stale.contains(id)) {
                throw new ProposalConflictException(
                        "Risk evidence newly stale since assessment; amend to reassess");
            }
        }
    }

    private static int rank(RiskLevel level) {
        return switch (level) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case UNKNOWN -> throw new IllegalArgumentException("UNKNOWN has no ordinal rank");
        };
    }

    /** Stored assessment evidence needed for the approval recheck. */
    private record StoredRisk(
            List<String> roots, int maxDepth, List<String> includeTypes,
            Map<String, String> levels, Set<String> stale) {

        static StoredRisk parse(String assessmentJson) {
            try {
                JsonNode document = JsonMapper.builder().build().readTree(assessmentJson);
                JsonNode settings = document.get("settings");
                List<String> roots = new ArrayList<>();
                for (JsonNode root : settings.get("roots")) {
                    roots.add(root.asText());
                }
                List<String> includeTypes = new ArrayList<>();
                for (JsonNode type : settings.get("includeTypes")) {
                    includeTypes.add(type.asText());
                }
                Map<String, String> levels = new LinkedHashMap<>();
                document.get("risk").get("levels").properties().forEach(
                        entry -> levels.put(entry.getKey(), entry.getValue().asText()));
                Set<String> stale = new HashSet<>();
                for (JsonNode id : document.get("risk").get("stale")) {
                    stale.add(id.asText());
                }
                int maxDepth = settings.get("maxDepth").asInt();
                if (roots.isEmpty() || includeTypes.isEmpty() || maxDepth < 1) {
                    throw new ProposalConflictException(
                            "Stored assessment settings are unusable: assessment is stale");
                }
                return new StoredRisk(roots, maxDepth, includeTypes, levels, stale);
            } catch (ProposalConflictException e) {
                throw e;
            } catch (Exception e) {
                throw new ProposalConflictException(
                        "Stored assessment is unreadable: assessment is stale");
            }
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A written decision reason is required");
        }
        if (reason.length() > 1024) {
            throw new IllegalArgumentException("Decision reason is too long");
        }
        return reason.trim();
    }

    private static void requireIdentity(String issuer, String subject) {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Authenticated issuer and subject are required");
        }
    }
}
