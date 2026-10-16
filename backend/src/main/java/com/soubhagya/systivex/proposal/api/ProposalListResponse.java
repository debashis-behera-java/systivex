package com.soubhagya.systivex.proposal.api;

import java.util.List;

/** Bounded proposal list page: capped items plus the totals to page by. */
public record ProposalListResponse(List<ProposalResponse> items, int page, int size, long total) {}
