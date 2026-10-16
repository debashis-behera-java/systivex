package com.soubhagya.systivex.proposal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical event hashing for the governance audit chain (Phase 11 S2).
 *
 * <p>Format {@code audit-event-v1}: SHA-256 over the UTF-8 bytes of one JSON
 * array with fixed field order —
 * {@code ["audit-event-v1", seq, occurredAt, actorIssuer, actorSubject,
 * action, outcome, proposalKey, revisionNumber, operationsHash,
 * assessmentHash, reason, correlationId, prevHash]}.
 * Instants render as ISO-8601 UTC, UUIDs and enums as their canonical
 * strings, absent values as JSON {@code null}. A JSON array (never raw
 * delimiter-joined strings) keeps nulls, Unicode, and escaping
 * unambiguous. The computed {@code event_hash} itself is never an input.
 * Both the appender and the verifier call {@link #hash} — there is only
 * one canonicalization implementation.
 */
public final class AuditHash {

    static final String FORMAT_TAG = "audit-event-v1";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private AuditHash() {}

    public static String hash(
            long seq,
            java.time.Instant occurredAt,
            String actorIssuer,
            String actorSubject,
            String action,
            String outcome,
            java.util.UUID proposalKey,
            int revisionNumber,
            String operationsHash,
            String assessmentHash,
            String reason,
            String correlationId,
            String prevHash) {
        List<Object> fields = new ArrayList<>();
        fields.add(FORMAT_TAG);
        fields.add(seq);
        fields.add(occurredAt == null ? null : occurredAt.toString());
        fields.add(actorIssuer);
        fields.add(actorSubject);
        fields.add(action);
        fields.add(outcome);
        fields.add(proposalKey == null ? null : proposalKey.toString());
        fields.add(revisionNumber);
        fields.add(operationsHash);
        fields.add(assessmentHash);
        fields.add(reason);
        fields.add(correlationId);
        fields.add(prevHash);
        byte[] bytes = JSON.writeValueAsBytes(fields);
        return HexFormat.of().formatHex(sha256(bytes));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
