package com.soubhagya.systivex.proposal.service;

import com.soubhagya.systivex.intelligence.SimulationOperation;
import com.soubhagya.systivex.intelligence.SimulationOperationType;
import com.soubhagya.systivex.intelligence.SimulationProposalValidator;
import com.soubhagya.systivex.twin.model.SystemRelationshipType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical operations form for governed proposals (Phase 11 S3, D29).
 *
 * <p>Draft creation resolves identifiers exactly like the simulation wire
 * format (via {@link SimulationProposalValidator} with empty analysis
 * roots) and stores the resolved, deterministically ordered JSON below.
 * The same canonical bytes feed {@link #sha256Hex}, so S4 assessment can
 * recompute and compare without trusting any caller-supplied assessment
 * data. Input order never affects the output: entity IDs and edge keys
 * are sorted before serialization.
 */
public final class ProposalCanonical {

    /**
     * Mapper with alphabetically sorted object keys, so the canonical
     * bytes are fully determined by content. PostgreSQL jsonb
     * normalizes insignificant whitespace on write — verifiers therefore
     * re-normalize stored documents with {@link #normalize} before
     * hashing instead of hashing raw column text.
     */
    private static final ObjectMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private ProposalCanonical() {}

    /**
     * Re-serializes any equivalent document to the canonical bytes. Goes
     * through plain maps (never a field-order-preserving tree), because
     * key sorting applies to map serialization only.
     */
    public static String normalize(String json) {
        try {
            Object unpacked = JSON.treeToValue(JSON.readTree(json), Object.class);
            return JSON.writeValueAsString(unpacked);
        } catch (Exception e) {
            throw new IllegalArgumentException("Stored operations are corrupt");
        }
    }

    public static String canonicalize(SimulationProposalValidator.ResolvedProposal proposal) {
        List<String> removedEntities =
                proposal.removedEntityIds().stream().map(UUID::toString).sorted().toList();
        List<Map<String, String>> added = edges(proposal.addedEdges());
        List<Map<String, String>> removed = edges(proposal.removedEdges());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("removedEntities", removedEntities);
        document.put("addedRelationships", added);
        document.put("removedRelationships", removed);
        return JSON.writeValueAsString(document);
    }

    private static List<Map<String, String>> edges(List<SimulationProposalValidator.ResolvedEdge> edges) {
        return edges.stream()
                .map(edge -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("source", edge.sourceId().toString());
                    entry.put("target", edge.targetId().toString());
                    entry.put("relationshipType", edge.type().name());
                    return entry;
                })
                .sorted(Comparator.comparing(
                        entry -> entry.get("source") + "|" + entry.get("target") + "|" + entry.get("relationshipType")))
                .toList();
    }

    /**
     * Parses stored canonical operations back into wire operations for
     * re-validation. Corrupt stored documents fail 400 — unreachable when
     * the recorded hash matches, since only this class writes the form.
     */
    public static List<SimulationOperation> parseOperations(String canonicalJson) {
        JsonNode document;
        try {
            document = JSON.readTree(canonicalJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Stored operations are corrupt");
        }
        List<SimulationOperation> operations = new ArrayList<>();
        for (JsonNode id : document.get("removedEntities")) {
            operations.add(new SimulationOperation(
                    SimulationOperationType.REMOVE_ENTITY, id.asText(), null, null, null));
        }
        for (String field : List.of("addedRelationships", "removedRelationships")) {
            SimulationOperationType type = "addedRelationships".equals(field)
                    ? SimulationOperationType.ADD_RELATIONSHIP
                    : SimulationOperationType.REMOVE_RELATIONSHIP;
            for (JsonNode edge : document.get(field)) {
                operations.add(new SimulationOperation(
                        type,
                        null,
                        edge.get("source").asText(),
                        edge.get("target").asText(),
                        SystemRelationshipType.valueOf(edge.get("relationshipType").asText())));
            }
        }
        return List.copyOf(operations);
    }

    public static String sha256Hex(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
