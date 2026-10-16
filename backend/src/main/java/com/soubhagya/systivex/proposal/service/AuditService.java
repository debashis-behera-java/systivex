package com.soubhagya.systivex.proposal.service;

import com.soubhagya.systivex.proposal.model.AuditAction;
import com.soubhagya.systivex.proposal.model.AuditChainHead;
import com.soubhagya.systivex.proposal.model.AuditEvent;
import com.soubhagya.systivex.proposal.model.AuditOutcome;
import com.soubhagya.systivex.proposal.repository.AuditChainHeadRepository;
import com.soubhagya.systivex.proposal.repository.AuditEventRepository;
import com.soubhagya.systivex.proposal.repository.ProposalRevisionRepository;
import com.soubhagya.systivex.twin.service.EntityNotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional append and read-only verification for the governance audit
 * chain (Phase 11 S2, D29). No proposal lifecycle logic lives here — only
 * the chain mechanics both later slices will share.
 *
 * <p>Append protocol, all in one transaction: lock the singleton head row,
 * allocate {@code last_seq + 1} (no database sequence, so rollbacks leave
 * no gaps), hash the canonical event fields with {@code prev_hash} set to
 * the head hash, insert the event, then advance the head. Any failure
 * rolls back both rows. Verification recomputes every link with the same
 * {@link AuditHash} implementation the appender uses.
 */
@Service
public class AuditService {

    private final AuditEventRepository events;

    private final AuditChainHeadRepository head;

    private final ProposalRevisionRepository revisions;

    public AuditService(
            AuditEventRepository events,
            AuditChainHeadRepository head,
            ProposalRevisionRepository revisions) {
        this.events = events;
        this.head = head;
        this.revisions = revisions;
    }

    /** Input for one chain append; every field lands in the hashed record. */
    public record AuditAppend(
            String actorIssuer,
            String actorSubject,
            AuditAction action,
            AuditOutcome outcome,
            UUID proposalKey,
            int revisionNumber,
            String operationsHash,
            String assessmentHash,
            String reason,
            String correlationId) {}

    @Transactional
    public AuditEvent append(AuditAppend append) {
        requireValid(append);
        if (revisions
                .findByProposalKeyAndRevisionNumber(append.proposalKey(), append.revisionNumber())
                .isEmpty()) {
            throw new EntityNotFoundException(
                    "Proposal revision not found: " + append.proposalKey() + " rev " + append.revisionNumber());
        }
        AuditChainHead chainHead =
                head.findHeadForUpdate().orElseThrow(() -> new IllegalStateException("Audit chain head is missing"));
        long seq = chainHead.getLastSeq() + 1;
        // Millis precision: PostgreSQL TIMESTAMPTZ keeps microseconds, so a
        // truncated-to-millis instant round-trips exactly and the hashed
        // timestamp always equals the stored one.
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String eventHash =
                AuditHash.hash(
                        seq,
                        occurredAt,
                        append.actorIssuer(),
                        append.actorSubject(),
                        append.action().name(),
                        append.outcome().name(),
                        append.proposalKey(),
                        append.revisionNumber(),
                        append.operationsHash(),
                        append.assessmentHash(),
                        append.reason(),
                        append.correlationId(),
                        chainHead.getLastHash());
        AuditEvent event =
                new AuditEvent(
                        seq,
                        append.actorIssuer(),
                        append.actorSubject(),
                        append.action(),
                        append.outcome(),
                        append.proposalKey(),
                        append.revisionNumber(),
                        chainHead.getLastHash(),
                        eventHash);
        event.setOccurredAt(occurredAt);
        event.setOperationsHash(append.operationsHash());
        event.setAssessmentHash(append.assessmentHash());
        event.setReason(append.reason());
        event.setCorrelationId(append.correlationId());
        AuditEvent stored = events.saveAndFlush(event);
        afterEventStored(stored);
        chainHead.setLastSeq(seq);
        chainHead.setLastHash(eventHash);
        head.saveAndFlush(chainHead);
        return stored;
    }

    /**
     * Test seam between the event insert and the head update. Production
     * code never overrides it; failure tests do, to prove both rows roll
     * back together.
     */
    protected void afterEventStored(AuditEvent stored) {}

    private static void requireValid(AuditAppend append) {
        if (append == null) {
            throw new IllegalArgumentException("Audit append input is required");
        }
        if (append.action() == null || append.outcome() == null) {
            throw new IllegalArgumentException("Audit action and outcome are required");
        }
        if (append.proposalKey() == null || append.revisionNumber() < 1) {
            throw new IllegalArgumentException("Audit proposal key and a positive revision are required");
        }
        if (append.actorSubject() == null || append.actorSubject().isBlank()) {
            throw new IllegalArgumentException("Audit actor subject is required");
        }
        if (append.actorIssuer() != null && append.actorIssuer().length() > 512) {
            throw new IllegalArgumentException("Audit actor issuer is too long");
        }
        if (append.reason() != null && append.reason().length() > 1024) {
            throw new IllegalArgumentException("Audit reason is too long");
        }
        if (append.correlationId() != null && append.correlationId().length() > 64) {
            throw new IllegalArgumentException("Audit correlation ID is too long");
        }
    }

    @Transactional(readOnly = true)
    public AuditVerification verify() {
        return head.findById(AuditChainHead.HEAD_ID)
                .map(this::verifyAgainst)
                .orElseGet(() -> new AuditVerification(
                        AuditVerification.BROKEN, 0, -1, null, "chain head is missing"));
    }

    private AuditVerification verifyAgainst(AuditChainHead chainHead) {
        List<AuditEvent> ordered = events.findAllByOrderBySeqAsc();
        if (ordered.isEmpty()) {
            if (chainHead.getLastSeq() == 0
                    && AuditChainHead.GENESIS_HASH.equals(chainHead.getLastHash())) {
                return new AuditVerification(AuditVerification.OK, 0, 0, null, "empty chain at genesis");
            }
            return new AuditVerification(
                    AuditVerification.BROKEN, 0, chainHead.getLastSeq(), 1L, "no events but head has advanced");
        }
        for (int i = 0; i < ordered.size(); i++) {
            AuditEvent event = ordered.get(i);
            long expectedSeq = i + 1L;
            if (event.getSeq() != expectedSeq) {
                return broken(ordered, chainHead, event.getSeq(), "sequence gap: expected seq " + expectedSeq);
            }
            String expectedPrev =
                    i == 0 ? AuditChainHead.GENESIS_HASH : ordered.get(i - 1).getEventHash();
            if (!expectedPrev.equals(event.getPrevHash())) {
                return broken(ordered, chainHead, event.getSeq(), "prev_hash does not match preceding event hash");
            }
            String recomputed =
                    AuditHash.hash(
                            event.getSeq(),
                            event.getOccurredAt(),
                            event.getActorIssuer(),
                            event.getActorSubject(),
                            event.getAction().name(),
                            event.getOutcome().name(),
                            event.getProposalKey(),
                            event.getRevisionNumber(),
                            event.getOperationsHash(),
                            event.getAssessmentHash(),
                            event.getReason(),
                            event.getCorrelationId(),
                            event.getPrevHash());
            if (!recomputed.equals(event.getEventHash())) {
                return broken(ordered, chainHead, event.getSeq(), "event_hash does not match stored fields");
            }
        }
        AuditEvent last = ordered.get(ordered.size() - 1);
        if (chainHead.getLastSeq() != ordered.size()) {
            return broken(ordered, chainHead, (long) ordered.size(), "head last_seq does not match stored event count");
        }
        if (!last.getEventHash().equals(chainHead.getLastHash())) {
            return broken(ordered, chainHead, last.getSeq(), "head last_hash does not match final event hash");
        }
        return new AuditVerification(
                AuditVerification.OK, ordered.size(), chainHead.getLastSeq(), null, "chain valid");
    }

    private static AuditVerification broken(
            List<AuditEvent> ordered, AuditChainHead chainHead, long atSeq, String detail) {
        return new AuditVerification(
                AuditVerification.BROKEN, ordered.size(), chainHead.getLastSeq(), atSeq, detail);
    }
}
