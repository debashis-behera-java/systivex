package com.soubhagya.systivex.intelligence;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.service.EntityNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Impact query validation shared by analyze, simulation, and governed
 * assessment (Phase 5 semantics, extracted from
 * {@link ImpactAnalysisService}).
 *
 * <p>Pure and repository-free: callers supply identifier resolution, so the
 * live query resolves against the repositories while assessment resolves
 * against its captured in-memory snapshot. Both paths share one validation
 * core — blank/oversized input fails 400, unknown identifiers 404 — with
 * identical trimming, bounds, defaults, and deterministic start ordering.
 */
public final class ImpactQueryValidator {

    static final int MAX_STARTS = 10;
    static final int DEFAULT_MAX_DEPTH = 6;
    static final int MAX_DEPTH_ALLOWED = 10;

    static final Set<SystemEntityType> DEFAULT_INCLUDE_TYPES =
            EnumSet.of(
                    SystemEntityType.SERVICE,
                    SystemEntityType.API,
                    SystemEntityType.DATABASE,
                    SystemEntityType.TABLE);

    private ImpactQueryValidator() {}

    /** Validated, resolved, and sorted query inputs. */
    public record ParsedInputs(
            List<SystemEntity> starts, int maxDepth, Set<SystemEntityType> includeTypes) {}

    /** Resolves one UUID-or-external-reference identifier; 404 on unknown. */
    @FunctionalInterface
    public interface EntityLookup {
        SystemEntity lookup(String raw);
    }

    /**
     * Validates raw query input and resolves every starting entity through
     * the caller-supplied lookup. Blank/oversized input fails with
     * {@link IllegalArgumentException} (400); unknown identifiers fail with
     * {@link EntityNotFoundException} (404).
     */
    public static ParsedInputs parseInputs(
            List<String> rawFrom,
            Integer rawMaxDepth,
            List<String> rawIncludeTypes,
            EntityLookup lookup) {
        return parseCore(rawFrom, rawMaxDepth, rawIncludeTypes, lookup);
    }

    /**
     * Snapshot-consistent variant: identical validation, but every
     * identifier resolves against the caller-supplied in-memory snapshot
     * only — no repository access, so simulation, risk, policy, and the
     * fingerprint describe the same captured state. UUID first, then
     * external reference, 404 on unknown.
     */
    public static ParsedInputs parseSnapshotInputs(
            Map<UUID, SystemEntity> entitiesById,
            List<String> rawFrom,
            Integer rawMaxDepth,
            List<String> rawIncludeTypes) {
        return parseCore(rawFrom, rawMaxDepth, rawIncludeTypes,
                raw -> snapshotLookup(entitiesById, raw));
    }

    private static ParsedInputs parseCore(
            List<String> rawFrom,
            Integer rawMaxDepth,
            List<String> rawIncludeTypes,
            EntityLookup lookup) {
        if (rawFrom == null || rawFrom.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one starting entity is required (query parameter 'from')");
        }
        List<String> cleaned = new ArrayList<>();
        for (String raw : rawFrom) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                        "Starting entity identifiers must not be blank");
            }
            cleaned.add(raw.trim());
        }
        if (cleaned.size() > MAX_STARTS) {
            throw new IllegalArgumentException(
                    "At most " + MAX_STARTS + " starting entities are supported");
        }
        int maxDepth = rawMaxDepth == null ? DEFAULT_MAX_DEPTH : rawMaxDepth;
        if (maxDepth < 1 || maxDepth > MAX_DEPTH_ALLOWED) {
            throw new IllegalArgumentException(
                    "maxDepth must be between 1 and " + MAX_DEPTH_ALLOWED);
        }
        Set<SystemEntityType> includeTypes = parseIncludeTypes(rawIncludeTypes);

        List<SystemEntity> starts = new ArrayList<>();
        for (String raw : cleaned) {
            starts.add(lookup.lookup(raw));
        }
        // Deterministic start order: externalRef first, then id.
        starts.sort(
                Comparator.comparing(
                                (SystemEntity e) -> e.getExternalRef(),
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(e -> e.getId().toString()));

        return new ParsedInputs(starts, maxDepth, includeTypes);
    }

    private static SystemEntity snapshotLookup(
            Map<UUID, SystemEntity> entitiesById, String raw) {
        UUID id = tryParseUuid(raw.trim());
        if (id != null && entitiesById.containsKey(id)) {
            return entitiesById.get(id);
        }
        for (SystemEntity entity : entitiesById.values()) {
            if (raw.trim().equals(entity.getExternalRef())) {
                return entity;
            }
        }
        throw new EntityNotFoundException("SystemEntity not found: " + raw);
    }

    private static Set<SystemEntityType> parseIncludeTypes(List<String> rawIncludeTypes) {
        if (rawIncludeTypes == null || rawIncludeTypes.isEmpty()) {
            return EnumSet.copyOf(DEFAULT_INCLUDE_TYPES);
        }
        Set<SystemEntityType> parsed = EnumSet.noneOf(SystemEntityType.class);
        for (String raw : rawIncludeTypes) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                        "includeTypes values must not be blank");
            }
            try {
                parsed.add(SystemEntityType.valueOf(raw.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown entity type: " + raw.trim());
            }
        }
        return parsed;
    }

    private static UUID tryParseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
