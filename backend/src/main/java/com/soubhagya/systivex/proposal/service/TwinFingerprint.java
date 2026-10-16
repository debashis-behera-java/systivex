package com.soubhagya.systivex.proposal.service;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Structural twin fingerprint for assessment binding (Phase 11 S4, D29).
 *
 * <p>Content-derived, never writer-instrumented — which is what covers
 * every write path by construction: CRUD inserts, sync structural
 * updates, and relationship inserts all change content. Each entity
 * contributes {@code id|type|name|externalRef|environment} plus its
 * structural metadata with the {@code runtime} subtree removed; each
 * relationship contributes {@code source|target|type}. {@code updated_at}
 * is never hashed: telemetry saves advance it on every observation run
 * even when nothing changed. Metadata serializes through Jackson with
 * recursively sorted keys so key order never affects the hash.
 */
public final class TwinFingerprint {

    static final String RUNTIME_KEY = "runtime";

    private static final ObjectMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private TwinFingerprint() {}

    public static String of(List<SystemEntity> entities, List<SystemRelationship> edges) {
        List<String> lines = new ArrayList<>();
        List<SystemEntity> orderedEntities = new ArrayList<>(entities);
        orderedEntities.sort((a, b) -> a.getId().toString().compareTo(b.getId().toString()));
        for (SystemEntity entity : orderedEntities) {
            lines.add("E|"
                    + entity.getId() + "|"
                    + entity.getType().name() + "|"
                    + entity.getName() + "|"
                    + entity.getExternalRef() + "|"
                    + entity.getEnvironment() + "|"
                    + JSON.writeValueAsString(structuralMetadata(entity.getMetadata())));
        }
        List<String> edgeLines = new ArrayList<>();
        for (SystemRelationship edge : edges) {
            edgeLines.add("R|"
                    + edge.getSource().getId() + "|"
                    + edge.getTarget().getId() + "|"
                    + edge.getRelationshipType().name());
        }
        edgeLines.sort(String::compareTo);
        lines.addAll(edgeLines);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static Map<String, Object> structuralMetadata(Map<String, Object> metadata) {
        Map<String, Object> copy = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        copy.remove(RUNTIME_KEY);
        return sorted(copy);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sorted(Map<String, Object> map) {
        Map<String, Object> ordered = new TreeMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            ordered.put(entry.getKey(), sortedValue(entry.getValue()));
        }
        return ordered;
    }

    private static Object sortedValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> cast = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                cast.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return sorted(cast);
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>();
            for (Object item : list) {
                items.add(sortedValue(item));
            }
            return List.copyOf(items);
        }
        return value;
    }
}
