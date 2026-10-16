package com.soubhagya.systivex.proposal.api;

import com.soubhagya.systivex.intelligence.SimulationOperation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Validated payload for drafting a governed change proposal (Phase 11 S3). */
public record CreateProposalRequest(
        @NotBlank @Size(max = 256) String title,
        @Size(max = 4000) String description,
        @NotNull @Size(min = 1, max = 100) List<SimulationOperation> operations) {}
