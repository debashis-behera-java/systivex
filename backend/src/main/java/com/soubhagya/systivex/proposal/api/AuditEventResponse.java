package com.soubhagya.systivex.proposal.api;

import com.soubhagya.systivex.proposal.model.AuditEvent;
import java.time.Instant;
import java.util.UUID;

/** Bounded read shape of one audit-chain event (Phase 11 S3). */
public record AuditEventResponse(
        UUID id,
        long seq,
        Instant occurredAt,
        String actorIssuer,
        String actorSubject,
        String action,
        String outcome,
        UUID proposalKey,
        int revisionNumber,
        String operationsHash,
        String assessmentHash,
        String reason,
        String correlationId,
        String prevHash,
        String eventHash) {

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
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
                event.getPrevHash(),
                event.getEventHash());
    }
}
