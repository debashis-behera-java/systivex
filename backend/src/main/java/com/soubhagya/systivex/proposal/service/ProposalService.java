package com.soubhagya.systivex.proposal.service;

import com.soubhagya.systivex.intelligence.ProposalConflictException;
import com.soubhagya.systivex.proposal.api.AuditEventResponse;
import com.soubhagya.systivex.proposal.api.CreateProposalRequest;
import com.soubhagya.systivex.proposal.api.ProposalListResponse;
import com.soubhagya.systivex.proposal.api.ProposalResponse;
import com.soubhagya.systivex.proposal.model.AuditAction;
import com.soubhagya.systivex.proposal.model.AuditOutcome;
import com.soubhagya.systivex.proposal.model.ProposalRevision;
import com.soubhagya.systivex.proposal.model.ProposalStatus;
import com.soubhagya.systivex.proposal.repository.AuditEventRepository;
import com.soubhagya.systivex.proposal.repository.ProposalRevisionRepository;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import com.soubhagya.systivex.twin.repository.SystemRelationshipRepository;
import com.soubhagya.systivex.twin.service.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Governed proposal drafts, withdrawal, and reads (Phase 11 S3, D29).
 *
 * <p>Draft creation resolves identifiers with impact-query semantics and
 * canonicalizes through {@link SimulationProposalValidator} (empty
 * analysis roots — root conflicts belong to assessment), storing the
 * resolved canonical operations plus their hash. The stored operations
 * are exactly what S4 assessment will recompute from; caller-supplied
 * assessment data is never accepted because the request carries none.
 * Every mutation shares one transaction with its audit append, so an
 * audit failure rolls the mutation back. Assessment, submission, and
 * amendment live in sibling services; approval and rejection arrive in
 * a later slice.
 */
@Service
public class ProposalService {

    private static final int MAX_PAGE_SIZE = 100;

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ProposalRevisionRepository revisions;

    private final AuditEventRepository events;

    private final AuditService audits;

    private final SystemEntityRepository entities;

    private final SystemRelationshipRepository relationships;

    public ProposalService(
            ProposalRevisionRepository revisions,
            AuditEventRepository events,
            AuditService audits,
            SystemEntityRepository entities,
            SystemRelationshipRepository relationships) {
        this.revisions = revisions;
        this.events = events;
        this.audits = audits;
        this.entities = entities;
        this.relationships = relationships;
    }

    @Transactional
    public ProposalResponse create(String issuer, String subject, CreateProposalRequest request) {
        requireIdentity(issuer, subject);
        if (request == null || request.operations() == null || request.operations().isEmpty()) {
            throw new IllegalArgumentException("At least one change operation is required");
        }
        ProposalOperations.Prepared prepared =
                ProposalOperations.prepare(
                        request.operations(),
                        ProposalOperations.edgeKeys(relationships.findAll()),
                        raw -> ProposalOperations.resolve(entities, raw));
        ProposalRevision revision =
                new ProposalRevision(
                        UUID.randomUUID(),
                        1,
                        ProposalStatus.DRAFT,
                        request.title().trim(),
                        prepared.canonicalJson(),
                        prepared.operationsHash(),
                        issuer,
                        subject);
        if (request.description() != null && !request.description().isBlank()) {
            revision.setDescription(request.description().trim());
        }
        ProposalRevision stored = revisions.saveAndFlush(revision);
        audits.append(
                new AuditService.AuditAppend(
                        issuer,
                        subject,
                        AuditAction.CREATED,
                        AuditOutcome.SUCCESS,
                        stored.getProposalKey(),
                        stored.getRevisionNumber(),
                        stored.getOperationsHash(),
                        null,
                        null,
                        UUID.randomUUID().toString()));
        return ProposalResponse.from(stored);
    }

    @Transactional
    public ProposalResponse withdraw(
            UUID proposalKey, String issuer, String subject, boolean isAdmin, String reason) {
        requireIdentity(issuer, subject);
        if (reason != null && reason.length() > 1024) {
            throw new IllegalArgumentException("Withdrawal reason is too long");
        }
        ProposalRevision latest = latestOrThrow(proposalKey);
        boolean proposer =
                issuer.equals(latest.getProposerIssuer()) && subject.equals(latest.getProposerSubject());
        if (!proposer && !isAdmin) {
            throw new AccessDeniedException("Only the proposer or a TWIN_ADMIN may withdraw this draft");
        }
        if (latest.getStatus() != ProposalStatus.DRAFT) {
            throw new ProposalConflictException(
                    "Only DRAFT revisions can be withdrawn; current status: " + latest.getStatus());
        }
        latest.setStatus(ProposalStatus.WITHDRAWN);
        if (reason != null && !reason.isBlank()) {
            latest.setReason(reason.trim());
        }
        try {
            revisions.saveAndFlush(latest);
        } catch (OptimisticLockingFailureException e) {
            throw new ProposalConflictException("Proposal was modified concurrently; refetch and retry");
        }
        audits.append(
                new AuditService.AuditAppend(
                        issuer,
                        subject,
                        AuditAction.WITHDRAWN,
                        AuditOutcome.SUCCESS,
                        latest.getProposalKey(),
                        latest.getRevisionNumber(),
                        latest.getOperationsHash(),
                        null,
                        latest.getReason(),
                        UUID.randomUUID().toString()));
        return ProposalResponse.from(latest);
    }

    @Transactional(readOnly = true)
    public ProposalListResponse list(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        if (size <= 0) {
            safeSize = DEFAULT_PAGE_SIZE;
        }
        Page<ProposalRevision> latest =
                revisions.findLatestRevisions(PageRequest.of(safePage, safeSize));
        return new ProposalListResponse(
                latest.getContent().stream().map(ProposalResponse::from).toList(),
                safePage,
                safeSize,
                latest.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ProposalResponse latest(UUID proposalKey) {
        return ProposalResponse.from(latestOrThrow(proposalKey));
    }

    @Transactional(readOnly = true)
    public List<ProposalResponse> history(UUID proposalKey) {
        List<ProposalRevision> history = revisions.findByProposalKeyOrderByRevisionNumberAsc(proposalKey);
        if (history.isEmpty()) {
            throw new EntityNotFoundException("Proposal not found: " + proposalKey);
        }
        return history.stream().map(ProposalResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProposalResponse revision(UUID proposalKey, int revisionNumber) {
        return ProposalResponse.from(
                revisions
                        .findByProposalKeyAndRevisionNumber(proposalKey, revisionNumber)
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Proposal revision not found: " + proposalKey + " rev " + revisionNumber)));
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> auditTrail(UUID proposalKey) {
        if (revisions.findByProposalKeyOrderByRevisionNumberAsc(proposalKey).isEmpty()) {
            throw new EntityNotFoundException("Proposal not found: " + proposalKey);
        }
        return events.findByProposalKeyOrderBySeqAsc(proposalKey).stream()
                .map(AuditEventResponse::from)
                .toList();
    }

    private ProposalRevision latestOrThrow(UUID proposalKey) {
        List<ProposalRevision> history = revisions.findByProposalKeyOrderByRevisionNumberAsc(proposalKey);
        if (history.isEmpty()) {
            throw new EntityNotFoundException("Proposal not found: " + proposalKey);
        }
        return history.get(history.size() - 1);
    }

    private static void requireIdentity(String issuer, String subject) {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Authenticated issuer and subject are required");
        }
    }
}
