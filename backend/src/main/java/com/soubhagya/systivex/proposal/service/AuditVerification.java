package com.soubhagya.systivex.proposal.service;

/**
 * Bounded, read-only result of one audit-chain verification (Phase 11 S2).
 *
 * <p>{@code status} is {@code OK} or {@code BROKEN}. {@code brokenAtSeq}
 * names the first sequence number where the break was detected (null when
 * {@code OK} or when the head row itself is missing/invalid).
 * {@code detail} carries one short machine-stable reason for triage — seqs
 * and hashes only, never secrets or unrelated proposal data. Verification
 * never repairs anything.
 */
public record AuditVerification(
        String status, long eventCount, long headSeq, Long brokenAtSeq, String detail) {

    public static final String OK = "OK";

    public static final String BROKEN = "BROKEN";
}
