package com.soubhagya.systivex.telemetry;

import java.util.List;
import java.util.Map;

/** Bounded per-service entry of an {@link ObservationReceipt}. */
public record ServiceObservation(
        String externalRef,
        String healthStatus,
        boolean healthAvailable,
        Map<String, Long> counters,
        boolean factsChanged,
        List<String> limitations) {}
