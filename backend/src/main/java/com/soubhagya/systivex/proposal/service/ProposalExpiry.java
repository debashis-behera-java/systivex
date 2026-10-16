package com.soubhagya.systivex.proposal.service;

import com.soubhagya.systivex.intelligence.ProposalConflictException;
import com.soubhagya.systivex.proposal.model.AuditAction;
import com.soubhagya.systivex.proposal.model.AuditOutcome;
import com.soubhagya.systivex.proposal.model.ProposalRevision;
import com.soubhagya.systivex.proposal.model.ProposalStatus;
import com.soubhagya.systivex.proposal.repository.ProposalRevisionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.ConcurrencyFailureException;

/**
 * Shared lazy-expiration mechanics for governed proposals (Phase 11 S6,
 * D29).
 *
 * <p>One rule for every path: the effective expiry is
 * {@code min(expires_at, revision.createdAt + 30 days)}, so each amendment
 * starts a fresh revision lifecycle. When it has passed, the caller
 * persists {@code EXPIRED} plus its audit event through
 * {@link #markExpired} and refuses the operation with
 * {@link com.soubhagya.systivex.intelligence.ProposalExpiredException}
 * (409 without rolling the expiry back). A caller that reaches this point
 * with a failing audit store rolls everything back instead — no partial
 * evidence, no changed state. Pure static helpers, following
 * {@link ProposalLookup}: repository access stays with the callers'
 * transactions, so the state change and its audit event always share one
 * atomic unit. No scheduler, no sweep.
 */
public final class ProposalExpiry {

    static final Duration HARD_MAX_TTL = Duration.ofDays(30);

    private ProposalExpiry() {}

    /** Effective expiry: the sooner of the assessment TTL and the hard maximum. */
    public static Instant effectiveExpiry(ProposalRevision revision) {
        Instant effective = revision.getExpiresAt();
        Instant hardMax = revision.getCreatedAt().plus(HARD_MAX_TTL);
        if (hardMax.isBefore(effective)) {
            effective = hardMax;
        }
        return effective;
    }

    /**
     * Persists {@code EXPIRED} and appends the {@code EXPIRED} audit event
     * in the caller's transaction. The caller must then throw
     * {@code ProposalExpiredException} (excluded from rollback) rather
     * than a rolling-back failure.
     */
    public static void markExpired(
            ProposalRevisionRepository revisions,
            AuditService audits,
            ProposalRevision latest,
            String issuer,
            String subject) {
        latest.setStatus(ProposalStatus.EXPIRED);
        try {
            revisions.saveAndFlush(latest);
        } catch (ConcurrencyFailureException e) {
            throw new ProposalConflictException("Proposal was modified concurrently; refetch and retry");
        }
        audits.append(
                new AuditService.AuditAppend(
                        issuer,
                        subject,
                        AuditAction.EXPIRED,
                        AuditOutcome.SUCCESS,
                        latest.getProposalKey(),
                        latest.getRevisionNumber(),
                        latest.getOperationsHash(),
                        latest.getAssessmentHash(),
                        null,
                        UUID.randomUUID().toString()));
    }
}
