package com.soubhagya.systivex.telemetry;

/**
 * Phase 6 provenance constants for the telemetry connector.
 *
 * <p>Runtime evidence is stored under {@link #RUNTIME_KEY} inside the
 * existing {@code metadata} JSONB of connector-managed
 * {@code target-service:*} entities. All other metadata keys keep their
 * Phase 4 meaning; in particular the top-level {@code managedBy} marker
 * stays {@code repository-connector} so Phase 4 sync ownership is
 * unaffected (sync preserves the {@code runtime} key, see
 * {@code TwinSyncService}).
 */
public final class TelemetryConnector {

    static final String CONNECTOR_ID = "telemetry-connector";
    static final String CONNECTOR_VERSION = "phase6-v1";

    /** Namespaced metadata key holding validated runtime evidence. */
    public static final String RUNTIME_KEY = "runtime";

    private TelemetryConnector() {}
}
