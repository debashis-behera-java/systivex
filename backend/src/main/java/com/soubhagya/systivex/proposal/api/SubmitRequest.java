package com.soubhagya.systivex.proposal.api;

/**
 * Binds a submission to the exact stored assessment (Phase 11 S4).
 * The internal JPA version is never exposed — staleness is detected
 * through the business revision number plus the persisted hashes.
 */
public record SubmitRequest(int revisionNumber, String operationsHash, String assessmentHash) {}
