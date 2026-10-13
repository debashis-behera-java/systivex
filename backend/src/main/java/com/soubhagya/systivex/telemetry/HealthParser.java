package com.soubhagya.systivex.telemetry;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 6 deterministic parser for Spring Boot Actuator health bodies.
 *
 * <p>Pure and bounded: extracts only the top-level {@code status} string
 * and validates it against the statuses Actuator actually reports. The raw
 * body is never stored; only the validated status string becomes a twin
 * fact. Anything else (missing/blank/unexpected status, non-object body,
 * oversized body) throws {@link IllegalArgumentException} so the caller
 * records the service as health-unavailable with a limitation instead of
 * inferring health.
 *
 * <p>Dependency-free by design (no JSON library): Actuator renders the
 * top-level status first ({@code {"status":"UP","components":{...}}}), so
 * the first textual {@code "status"} member is the service status. The
 * value itself is still validated against the known set — an unexpected
 * shape fails closed rather than guessing.
 */
public final class HealthParser {

    static final int MAX_CHARS = 65536;

    /** Health statuses Actuator reports; anything else is unexpected input. */
    private static final Set<String> KNOWN_STATUSES =
            Set.of("UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN");

    private static final Pattern STATUS_MEMBER =
            Pattern.compile("\"status\"\\s*:\\s*\"([^\"]*)\"");

    private HealthParser() {}

    /**
     * Returns the validated health status.
     *
     * @throws IllegalArgumentException when the body is missing, oversized,
     *     not a JSON object, or carries an unexpected status value
     */
    public static String parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Health response is empty");
        }
        String trimmed = body.trim();
        if (trimmed.length() > MAX_CHARS) {
            throw new IllegalArgumentException("Health response exceeds size bound");
        }
        if (!trimmed.startsWith("{")) {
            throw new IllegalArgumentException("Health response is not a JSON object");
        }
        Matcher status = STATUS_MEMBER.matcher(trimmed);
        if (!status.find()) {
            throw new IllegalArgumentException("Health response carries no status field");
        }
        String value = status.group(1).trim();
        if (!KNOWN_STATUSES.contains(value)) {
            throw new IllegalArgumentException("Unexpected health status: " + value);
        }
        return value;
    }
}
