package com.soubhagya.systivex.proposal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One immutable revision of a governed change proposal (Phase 11 S1, D29).
 *
 * <p>Identity is {@code (proposalKey, revisionNumber)} — stable across
 * amendments. {@code jpaVersion} is the JPA optimistic-locking field only:
 * it is never serialized, hashed, or exposed in any API contract, and must
 * not be confused with the business {@code revisionNumber}.
 *
 * <p>Schema is owned by Flyway (see {@code db/migration/V2__proposal_governance.sql});
 * mapping nullability/names must stay aligned with it (ddl-auto=validate).
 * No lifecycle logic lives here; transitions arrive in later slices.
 */
@Entity
@Table(
        name = "proposal_revision",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_proposal_revision_key_number",
                        columnNames = {"proposal_key", "revision_number"}))
public class ProposalRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "proposal_key", nullable = false)
    private UUID proposalKey;

    @Column(name = "revision_number", nullable = false)
    private int revisionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24, nullable = false)
    private ProposalStatus status;

    @Column(name = "title", length = 256, nullable = false)
    private String title;

    @Column(name = "description", length = 4000)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "operations", columnDefinition = "jsonb", nullable = false)
    private String operations;

    @Column(name = "operations_hash", length = 64, nullable = false)
    private String operationsHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assessment", columnDefinition = "jsonb")
    private String assessment;

    @Column(name = "assessment_hash", length = 64)
    private String assessmentHash;

    @Column(name = "policy_decision", length = 16)
    private String policyDecision;

    @Column(name = "policy_version", length = 32)
    private String policyVersion;

    @Column(name = "risk_taxonomy", length = 32)
    private String riskTaxonomy;

    @Column(name = "twin_fingerprint", length = 64)
    private String twinFingerprint;

    @Column(name = "snapshot_observed_at")
    private Instant snapshotObservedAt;

    @Column(name = "proposer_issuer", length = 512, nullable = false)
    private String proposerIssuer;

    @Column(name = "proposer_subject", length = 512, nullable = false)
    private String proposerSubject;

    @Column(name = "approver_issuer", length = 512)
    private String approverIssuer;

    @Column(name = "approver_subject", length = 512)
    private String approverSubject;

    @Column(name = "reason", length = 1024)
    private String reason;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "jpa_version", nullable = false)
    private long jpaVersion;

    protected ProposalRevision() {}

    public ProposalRevision(
            UUID proposalKey,
            int revisionNumber,
            ProposalStatus status,
            String title,
            String operations,
            String operationsHash,
            String proposerIssuer,
            String proposerSubject) {
        this.proposalKey = proposalKey;
        this.revisionNumber = revisionNumber;
        this.status = status;
        this.title = title;
        this.operations = operations;
        this.operationsHash = operationsHash;
        this.proposerIssuer = proposerIssuer;
        this.proposerSubject = proposerSubject;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getProposalKey() {
        return proposalKey;
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public ProposalStatus getStatus() {
        return status;
    }

    public void setStatus(ProposalStatus status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOperations() {
        return operations;
    }

    public String getOperationsHash() {
        return operationsHash;
    }

    public String getAssessment() {
        return assessment;
    }

    public void setAssessment(String assessment) {
        this.assessment = assessment;
    }

    public String getAssessmentHash() {
        return assessmentHash;
    }

    public void setAssessmentHash(String assessmentHash) {
        this.assessmentHash = assessmentHash;
    }

    public String getPolicyDecision() {
        return policyDecision;
    }

    public void setPolicyDecision(String policyDecision) {
        this.policyDecision = policyDecision;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public String getRiskTaxonomy() {
        return riskTaxonomy;
    }

    public void setRiskTaxonomy(String riskTaxonomy) {
        this.riskTaxonomy = riskTaxonomy;
    }

    public String getTwinFingerprint() {
        return twinFingerprint;
    }

    public void setTwinFingerprint(String twinFingerprint) {
        this.twinFingerprint = twinFingerprint;
    }

    public Instant getSnapshotObservedAt() {
        return snapshotObservedAt;
    }

    public void setSnapshotObservedAt(Instant snapshotObservedAt) {
        this.snapshotObservedAt = snapshotObservedAt;
    }

    public String getProposerIssuer() {
        return proposerIssuer;
    }

    public String getProposerSubject() {
        return proposerSubject;
    }

    public String getApproverIssuer() {
        return approverIssuer;
    }

    public void setApproverIssuer(String approverIssuer) {
        this.approverIssuer = approverIssuer;
    }

    public String getApproverSubject() {
        return approverSubject;
    }

    public void setApproverSubject(String approverSubject) {
        this.approverSubject = approverSubject;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getJpaVersion() {
        return jpaVersion;
    }
}
