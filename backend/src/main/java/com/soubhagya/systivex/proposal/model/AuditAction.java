package com.soubhagya.systivex.proposal.model;

/** Governed lifecycle actions recorded in the audit chain (Phase 11, D29). */
public enum AuditAction {
    CREATED,
    ASSESSED,
    SUBMITTED,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    EXPIRED,
    SUPERSEDED
}
