package com.soubhagya.systivex.telemetry;

import java.util.List;
import java.util.Map;

/**
 * Validated runtime facts for one twin service, ready to persist.
 *
 * <p>{@code counters} holds logical counter names to values and contains
 * only families observed in that service's own exposition that belong to
 * the service's allowlisted set. Families the service defines but did not
 * expose are absent — never inferred as zero. {@code limitations} records
 * every unavailable or skipped piece of evidence so missing data can never
 * be misread as a successful observation.
 */
public record ObservedServiceFacts(
        String externalRef,
        int port,
        String healthStatus,
        boolean healthAvailable,
        Map<String, Long> counters,
        List<String> limitations) {}
