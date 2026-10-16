-- Phase 11 S1: governed change proposals and tamper-evident audit.
-- Flyway is the schema authority; Hibernate only validates (ddl-auto=validate).
-- This migration only adds governance tables; twin tables are untouched and
-- existing twin rows survive unchanged. Lifecycle states are VARCHAR with a
-- CHECK constraint (no PostgreSQL enums, matching V1 conventions).

CREATE TABLE proposal_revision (
    id                UUID         NOT NULL PRIMARY KEY,
    proposal_key      UUID         NOT NULL,
    revision_number   INTEGER      NOT NULL,
    status            VARCHAR(24)  NOT NULL,
    title             VARCHAR(256) NOT NULL,
    description       VARCHAR(4000),
    operations        JSONB        NOT NULL,
    operations_hash   VARCHAR(64)     NOT NULL,
    assessment        JSONB,
    assessment_hash   VARCHAR(64),
    policy_decision   VARCHAR(16),
    policy_version    VARCHAR(32),
    risk_taxonomy     VARCHAR(32),
    twin_fingerprint  VARCHAR(64),
    snapshot_observed_at TIMESTAMPTZ,
    proposer_issuer   VARCHAR(512) NOT NULL,
    proposer_subject  VARCHAR(512) NOT NULL,
    approver_issuer   VARCHAR(512),
    approver_subject  VARCHAR(512),
    reason            VARCHAR(1024),
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    jpa_version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_proposal_revision_key_number UNIQUE (proposal_key, revision_number),
    CONSTRAINT chk_proposal_revision_number CHECK (revision_number >= 1),
    CONSTRAINT chk_proposal_revision_status CHECK (status IN (
        'DRAFT', 'ASSESSED', 'AWAITING_APPROVAL', 'APPROVED',
        'REJECTED', 'WITHDRAWN', 'EXPIRED', 'SUPERSEDED'))
);

CREATE INDEX idx_proposal_revision_key ON proposal_revision (proposal_key);
CREATE INDEX idx_proposal_revision_status ON proposal_revision (status);

CREATE TABLE audit_event (
    id                UUID         NOT NULL PRIMARY KEY,
    seq               BIGINT       NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_issuer      VARCHAR(512),
    actor_subject     VARCHAR(512) NOT NULL,
    action            VARCHAR(24)  NOT NULL,
    outcome           VARCHAR(16)  NOT NULL,
    proposal_key      UUID         NOT NULL,
    revision_number   INTEGER      NOT NULL,
    operations_hash   VARCHAR(64),
    assessment_hash   VARCHAR(64),
    reason            VARCHAR(1024),
    correlation_id    VARCHAR(64),
    prev_hash         VARCHAR(64)  NOT NULL,
    event_hash        VARCHAR(64)     NOT NULL,
    CONSTRAINT uq_audit_event_seq UNIQUE (seq),
    CONSTRAINT chk_audit_event_seq CHECK (seq >= 1),
    CONSTRAINT chk_audit_event_action CHECK (action IN (
        'CREATED', 'ASSESSED', 'SUBMITTED', 'APPROVED',
        'REJECTED', 'WITHDRAWN', 'EXPIRED', 'SUPERSEDED')),
    CONSTRAINT chk_audit_event_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT fk_audit_event_revision FOREIGN KEY (proposal_key, revision_number)
        REFERENCES proposal_revision (proposal_key, revision_number)
);

CREATE INDEX idx_audit_event_proposal ON audit_event (proposal_key, revision_number);
CREATE INDEX idx_audit_event_occurred ON audit_event (occurred_at);

-- Single-row chain head. Sequence numbers are allocated by the application
-- under a row lock on this row (never from a non-transactional sequence, so
-- a rolled-back transaction leaves no gap). The genesis row uses the
-- 'GENESIS' marker, which fits the VARCHAR hash columns by design.
CREATE TABLE audit_chain_head (
    id         INTEGER      NOT NULL PRIMARY KEY,
    last_seq   BIGINT       NOT NULL,
    last_hash  VARCHAR(64)  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_audit_chain_head_singleton CHECK (id = 1)
);

INSERT INTO audit_chain_head (id, last_seq, last_hash) VALUES (1, 0, 'GENESIS');
