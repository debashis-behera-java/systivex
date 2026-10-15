package com.soubhagya.systivex.intelligence;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Change-simulation request: analysis roots plus a change proposal.
 *
 * <p>{@code from} (1–10 UUIDs or external references), {@code maxDepth}
 * (1–10, default 6), and {@code includeTypes} (default
 * SERVICE/API/DATABASE/TABLE) follow the existing impact-query defaults and
 * validation exactly. {@code operations} is the unexecuted proposal: only
 * {@code REMOVE_ENTITY}, {@code ADD_RELATIONSHIP}, and
 * {@code REMOVE_RELATIONSHIP} are accepted. A missing {@code operations}
 * list simulates the null proposal (before equals after).
 */
public record SimulationRequest(
        @JsonProperty("from") List<String> from,
        @JsonProperty("maxDepth") @JsonAlias("max_depth") Integer maxDepth,
        @JsonProperty("includeTypes") @JsonAlias("include_types") List<String> includeTypes,
        @JsonProperty("operations") List<SimulationOperation> operations) {}
