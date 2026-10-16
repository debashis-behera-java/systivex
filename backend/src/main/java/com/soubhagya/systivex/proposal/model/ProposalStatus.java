package com.soubhagya.systivex.proposal.model;

/** Lifecycle states of one immutable proposal revision (Phase 11, D29). */
public enum ProposalStatus {
    DRAFT,
    ASSESSED,
    AWAITING_APPROVAL,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    EXPIRED,
    SUPERSEDED
}
