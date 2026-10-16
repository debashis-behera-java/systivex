package com.soubhagya.systivex.proposal.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Validated payload for approving or rejecting a submitted proposal
 * (Phase 11 S5, D29). A written reason is mandatory; the actor identity
 * always comes from the JWT, never from this body.
 */
public record DecisionRequest(@NotBlank @Size(max = 1024) String reason) {}
