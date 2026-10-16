package com.soubhagya.systivex.proposal.api;

import jakarta.validation.constraints.Size;

/** Optional withdrawal reason; the actor identity always comes from the JWT. */
public record WithdrawRequest(@Size(max = 1024) String reason) {}
