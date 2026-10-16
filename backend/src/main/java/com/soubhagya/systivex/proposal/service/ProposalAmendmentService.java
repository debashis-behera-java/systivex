package com.soubhagya.systivex.proposal.service;

import com.soubhagya.systivex.intelligence.ProposalConflictException;
import com.soubhagya.systivex.proposal.api.CreateProposalRequest;
import com.soubhagya.systivex.proposal.api.ProposalResponse;
import com.soubhagya.systivex.proposal.model.AuditAction;
import com.soubhagya.systivex.proposal.model.AuditOutcome;
import com.soubhagya.systivex.proposal.model.ProposalRevision;
import com.soubhagya.systivex.proposal.model.ProposalStatus;
import com.soubhagya.systivex.proposal.repository.ProposalRevisionRepository;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import com.soubhagya.systivex.twin.repository.SystemRelationshipRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proposal amendments as new revisions (Phase 11 S4, D29).
 *
 * <p>An amendment never overwrites: the latest eligible non-terminal
 * revision becomes {@code SUPERSEDED} and a new {@code DRAFT} revision
 * row carries the re-validated, re-canonicalized operations with no
 * inherited assessment, approval, or decision. Both row writes share one
 * transaction with the {@code SUPERSEDED} and {@code CREATED} audit
 * events. Concurrent amendments serialize on the optimistic lock — at
 * most one new revision number is ever issued, and the loser retries
 * against the new latest revision.
 */
@Service
public class ProposalAmendmentService {

    private final ProposalRevisionRepository revisions;

    private final AuditService audits;

    private final SystemEntityRepository entities;

    private final SystemRelationshipRepository relationships;

    public ProposalAmendmentService(
            ProposalRevisionRepository revisions,
            AuditService audits,
            SystemEntityRepository entities,
            SystemRelationshipRepository relationships) {
        this.revisions = revisions;
        this.audits = audits;
        this.entities = entities;
        this.relationships = relationships;
    }

    @Transactional
    public ProposalResponse amend(
            UUID proposalKey,
            String issuer,
            String subject,
            boolean isAdmin,
            CreateProposalRequest request) {
        requireIdentity(issuer, subject);
        if (request == null) {
            throw new IllegalArgumentException("Amended title and operations are required");
        }
        ProposalRevision latest = ProposalLookup.latestOrThrow(revisions, proposalKey);
        boolean proposer = issuer.equals(latest.getProposerIssuer())
                && subject.equals(latest.getProposerSubject());
        if (!proposer && !isAdmin) {
            throw new AccessDeniedException("Only the proposer or a TWIN_ADMIN may amend this proposal");
        }
        if (latest.getStatus() != ProposalStatus.DRAFT
                && latest.getStatus() != ProposalStatus.ASSESSED
                && latest.getStatus() != ProposalStatus.AWAITING_APPROVAL) {
            throw new ProposalConflictException(
                    "Only non-terminal revisions can be amended; current status: " + latest.getStatus());
        }
        ProposalOperations.Prepared prepared =
                ProposalOperations.prepare(
                        request.operations(),
                        ProposalOperations.edgeKeys(relationships.findAll()),
                        raw -> ProposalOperations.resolve(entities, raw));
        latest.setStatus(ProposalStatus.SUPERSEDED);
        try {
            revisions.saveAndFlush(latest);
        } catch (OptimisticLockingFailureException e) {
            throw new ProposalConflictException("Proposal was modified concurrently; refetch and retry");
        }
        ProposalRevision next = new ProposalRevision(
                latest.getProposalKey(),
                latest.getRevisionNumber() + 1,
                ProposalStatus.DRAFT,
                request.title().trim(),
                prepared.canonicalJson(),
                prepared.operationsHash(),
                latest.getProposerIssuer(),
                latest.getProposerSubject());
        if (request.description() != null && !request.description().isBlank()) {
            next.setDescription(request.description().trim());
        }
        try {
            revisions.saveAndFlush(next);
        } catch (DataIntegrityViolationException e) {
            throw new ProposalConflictException("Proposal was modified concurrently; refetch and retry");
        }
        audits.append(
                new AuditService.AuditAppend(
                        issuer,
                        subject,
                        AuditAction.SUPERSEDED,
                        AuditOutcome.SUCCESS,
                        latest.getProposalKey(),
                        latest.getRevisionNumber(),
                        latest.getOperationsHash(),
                        latest.getAssessmentHash(),
                        null,
                        UUID.randomUUID().toString()));
        audits.append(
                new AuditService.AuditAppend(
                        issuer,
                        subject,
                        AuditAction.CREATED,
                        AuditOutcome.SUCCESS,
                        next.getProposalKey(),
                        next.getRevisionNumber(),
                        next.getOperationsHash(),
                        null,
                        null,
                        UUID.randomUUID().toString()));
        return ProposalResponse.from(next);
    }

    private static void requireIdentity(String issuer, String subject) {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Authenticated issuer and subject are required");
        }
    }
}
