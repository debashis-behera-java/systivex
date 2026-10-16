package com.soubhagya.systivex.proposal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * One entry of the append-only governance audit chain (Phase 11 S1, D29).
 *
 * <p>{@code seq} is allocated by the application under the
 * {@code audit_chain_head} row lock — never from a non-transactional
 * database sequence, so a rolled-back transaction leaves no gap.
 * {@code eventHash} chains via {@code prevHash}; the genesis event uses
 * the {@code GENESIS} marker. No append or verification logic lives here;
 * that arrives in a later slice, and nothing here writes audit events.
 *
 * <p>Schema is owned by Flyway (see {@code db/migration/V2__proposal_governance.sql});
 * mapping nullability/names must stay aligned with it (ddl-auto=validate).
 */
@Entity
@Table(
        name = "audit_event",
        uniqueConstraints =
                @UniqueConstraint(name = "uq_audit_event_seq", columnNames = "seq"))
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "seq", nullable = false, updatable = false)
    private long seq;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_issuer", length = 512)
    private String actorIssuer;

    @Column(name = "actor_subject", length = 512, nullable = false)
    private String actorSubject;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 24, nullable = false, updatable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16, nullable = false, updatable = false)
    private AuditOutcome outcome;

    @Column(name = "proposal_key", nullable = false, updatable = false)
    private UUID proposalKey;

    @Column(name = "revision_number", nullable = false, updatable = false)
    private int revisionNumber;

    @Column(name = "operations_hash", length = 64)
    private String operationsHash;

    @Column(name = "assessment_hash", length = 64)
    private String assessmentHash;

    @Column(name = "reason", length = 1024)
    private String reason;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "prev_hash", length = 64, nullable = false, updatable = false)
    private String prevHash;

    @Column(name = "event_hash", length = 64, nullable = false, updatable = false)
    private String eventHash;

    protected AuditEvent() {}

    public AuditEvent(
            long seq,
            String actorIssuer,
            String actorSubject,
            AuditAction action,
            AuditOutcome outcome,
            UUID proposalKey,
            int revisionNumber,
            String prevHash,
            String eventHash) {
        this.seq = seq;
        this.actorIssuer = actorIssuer;
        this.actorSubject = actorSubject;
        this.action = action;
        this.outcome = outcome;
        this.proposalKey = proposalKey;
        this.revisionNumber = revisionNumber;
        this.prevHash = prevHash;
        this.eventHash = eventHash;
    }

    @PrePersist
    void onCreate() {
        if (this.occurredAt == null) {
            this.occurredAt = Instant.now();
        }
    }

    /**
     * Sets the event timestamp explicitly. The audit service always sets
     * this before hashing so the stored timestamp and the hashed timestamp
     * are identical; the {@code PrePersist} fallback above only covers
     * direct persistence outside the service.
     */
    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public long getSeq() {
        return seq;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActorIssuer() {
        return actorIssuer;
    }

    public String getActorSubject() {
        return actorSubject;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public UUID getProposalKey() {
        return proposalKey;
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public String getOperationsHash() {
        return operationsHash;
    }

    public void setOperationsHash(String operationsHash) {
        this.operationsHash = operationsHash;
    }

    public String getAssessmentHash() {
        return assessmentHash;
    }

    public void setAssessmentHash(String assessmentHash) {
        this.assessmentHash = assessmentHash;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getEventHash() {
        return eventHash;
    }
}
