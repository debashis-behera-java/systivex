package com.soubhagya.systivex.intelligence;

/**
 * A governed proposal decision blocked by lapsed TTL (Phase 11 S5, D29).
 *
 * <p>Separate from {@link ProposalConflictException} for one transactional
 * reason: lazy expiration persists the {@code EXPIRED} state and its audit
 * event before refusing the decision, so this failure must commit rather
 * than roll back. It maps to the same fixed 409 contract.
 */
public class ProposalExpiredException extends RuntimeException {

    public ProposalExpiredException(String message) {
        super(message);
    }
}
