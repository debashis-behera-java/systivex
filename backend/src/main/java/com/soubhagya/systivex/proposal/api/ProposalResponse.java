package com.soubhagya.systivex.proposal.api;

import com.soubhagya.systivex.proposal.model.ProposalRevision;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bounded read shape of one proposal revision (Phase 11 S3). Never
 * carries the JPA optimistic-locking version, persistence internals,
 * secrets, or JWT claims — only the governed record.
 */
public record ProposalResponse(
        UUID proposalKey,
        int revisionNumber,
        String status,
        String title,
        String description,
        JsonNode operations,
        String operationsHash,
        JsonNode assessment,
        String assessmentHash,
        String policyDecision,
        String policyVersion,
        String riskTaxonomy,
        String twinFingerprint,
        Instant snapshotObservedAt,
        String proposerIssuer,
        String proposerSubject,
        String approverIssuer,
        String approverSubject,
        String reason,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ProposalResponse from(ProposalRevision revision) {
        return new ProposalResponse(
                revision.getProposalKey(),
                revision.getRevisionNumber(),
                revision.getStatus().name(),
                revision.getTitle(),
                revision.getDescription(),
                json(revision.getOperations()),
                revision.getOperationsHash(),
                revision.getAssessment() == null ? null : json(revision.getAssessment()),
                revision.getAssessmentHash(),
                revision.getPolicyDecision(),
                revision.getPolicyVersion(),
                revision.getRiskTaxonomy(),
                revision.getTwinFingerprint(),
                revision.getSnapshotObservedAt(),
                revision.getProposerIssuer(),
                revision.getProposerSubject(),
                revision.getApproverIssuer(),
                revision.getApproverSubject(),
                revision.getReason(),
                revision.getExpiresAt(),
                revision.getCreatedAt(),
                revision.getUpdatedAt());
    }

    private static JsonNode json(String document) {
        return JsonMapper.builder().build().readTree(document);
    }
}
