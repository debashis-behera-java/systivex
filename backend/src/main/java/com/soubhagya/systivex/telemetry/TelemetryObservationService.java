package com.soubhagya.systivex.telemetry;

import com.soubhagya.systivex.observation.OwnershipConflictException;
import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import com.soubhagya.systivex.twin.service.EntityNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 6 observation: annotates the existing connector-managed
 * {@code target-service:*} entities with validated runtime evidence.
 *
 * <p>Flow per run: load managed services (sorted) → validate identity
 * (port) → collect raw snapshots → validate every payload (parsers fail
 * closed; a privacy or allowlist violation aborts the whole run before any
 * write) → apply all changes in one transaction. Transport failures are
 * not validation failures: an unreachable service is recorded as
 * unavailable with a limitation, and a run where nothing was reachable
 * still returns a receipt that proves nothing was observed (zero updates
 * plus explicit limitations) instead of claiming success.
 *
 * <p>Writes touch only the namespaced {@code runtime} metadata key of
 * matched services. Manual rows, all other metadata keys, and the entire
 * relationship graph are never modified, so Phase 5 impact semantics are
 * unaffected. Trace/log presence is deliberately not collected: no
 * existing source offers a privacy-safe presence signal without bulk log
 * import or raw trace storage, so the receipt carries that limitation
 * instead of fabricated evidence.
 */
@Service
public class TelemetryObservationService {

    static final String NOTE =
            "Runtime evidence annotated from validated local health/counter sources;"
                    + " missing evidence is reported as unavailable, never inferred."
                    + " Verify against the twin and live backend state before acting on it.";

    private final SystemEntityRepository entities;
    private final TelemetrySource source;

    public TelemetryObservationService(SystemEntityRepository entities, TelemetrySource source) {
        this.entities = entities;
        this.source = source;
    }

    @Transactional
    public ObservationReceipt observe() {
        Instant now = Instant.now();
        List<SystemEntity> managed = managedServices();
        if (managed.isEmpty()) {
            throw new EntityNotFoundException(
                    "No connector-managed target services observed;"
                            + " synchronize the twin first (POST /api/v1/twin/sync)");
        }
        Map<SystemEntity, Integer> ports = validateIdentity(managed);
        List<ObservedServiceFacts> collected = collectAll(managed, ports);

        int updated = 0;
        int unchanged = 0;
        List<ServiceObservation> services = new ArrayList<>();
        for (ObservedServiceFacts facts : collected) {
            SystemEntity entity = findManaged(managed, facts.externalRef());
            boolean factsChanged = applyFacts(entity, facts, now);
            if (factsChanged) {
                updated++;
            } else {
                unchanged++;
            }
            entities.save(entity);
            services.add(
                    new ServiceObservation(
                            facts.externalRef(),
                            facts.healthStatus(),
                            facts.healthAvailable(),
                            facts.counters(),
                            factsChanged,
                            facts.limitations()));
        }
        List<String> limitations = new ArrayList<>();
        limitations.add(
                "trace/log presence not collected: no privacy-safe presence"
                        + " signal exists without bulk log import or raw trace storage");
        return new ObservationReceipt(
                now,
                services.size(),
                updated,
                unchanged,
                List.copyOf(services),
                List.copyOf(limitations),
                NOTE);
    }

    private List<SystemEntity> managedServices() {
        return entities.findAll().stream()
                .filter(e -> e.getType() == SystemEntityType.SERVICE)
                .filter(e -> e.getExternalRef() != null && e.getExternalRef().startsWith("target-service:"))
                .filter(TelemetryObservationService::isConnectorOwned)
                .sorted(Comparator.comparing(SystemEntity::getExternalRef))
                .toList();
    }

    private static boolean isConnectorOwned(SystemEntity entity) {
        return "repository-connector".equals(
                entity.getMetadata() == null ? null : entity.getMetadata().get("managedBy"));
    }

    private static Map<SystemEntity, Integer> validateIdentity(List<SystemEntity> managed) {
        Map<SystemEntity, Integer> ports = new LinkedHashMap<>();
        for (SystemEntity entity : managed) {
            String shortName = entity.getExternalRef().substring("target-service:".length());
            if (!AllowedCounters.SERVICE_FAMILIES.containsKey(shortName)) {
                throw new OwnershipConflictException(
                        "Unknown service mapping for " + entity.getExternalRef()
                                + "; telemetry ingestion supports only known target services");
            }
            Object port = entity.getMetadata() == null ? null : entity.getMetadata().get("port");
            int value = toPort(port);
            if (value < 1 || value > 65535) {
                throw new OwnershipConflictException(
                        "Service " + entity.getExternalRef()
                                + " has no valid port in twin metadata; cannot observe");
            }
            ports.put(entity, value);
        }
        return ports;
    }

    private static int toPort(Object port) {
        if (port instanceof Number number) {
            return number.intValue();
        }
        if (port instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private List<ObservedServiceFacts> collectAll(
            List<SystemEntity> managed, Map<SystemEntity, Integer> ports) {
        List<ObservedServiceFacts> collected = new ArrayList<>();
        for (SystemEntity entity : managed) {
            TelemetrySource.Snapshot snapshot = source.fetch(ports.get(entity));
            collected.add(validateSnapshot(entity, snapshot));
        }
        return collected;
    }

    private static ObservedServiceFacts validateSnapshot(
            SystemEntity entity, TelemetrySource.Snapshot snapshot) {
        String shortName = entity.getExternalRef().substring("target-service:".length());
        Set<String> expected = AllowedCounters.SERVICE_FAMILIES.get(shortName);
        List<String> limitations = new ArrayList<>();

        String healthStatus = null;
        boolean healthAvailable = false;
        if (snapshot != null && snapshot.healthBody() != null) {
            try {
                healthStatus = HealthParser.parse(snapshot.healthBody());
                healthAvailable = true;
            } catch (IllegalArgumentException e) {
                limitations.add("health unavailable (" + e.getMessage() + ")");
            }
        } else {
            limitations.add("health unavailable (no response from service)");
        }

        Map<String, Long> counters = new LinkedHashMap<>();
        if (snapshot != null && snapshot.metricsBody() != null) {
            // Parser failure aborts the whole run (fail-closed): the payload
            // was reachable but invalid or privacy-sensitive.
            Map<String, Long> parsed = PrometheusCounterParser.parse(snapshot.metricsBody());
            for (Map.Entry<String, Long> entry : parsed.entrySet()) {
                if (expected.contains(entry.getKey())) {
                    counters.put(entry.getKey(), entry.getValue());
                }
            }
            for (String family : expected) {
                if (!counters.containsKey(family)) {
                    limitations.add(
                            "counter " + family + " not exposed by service; no value inferred");
                }
            }
        } else {
            limitations.add("counters unavailable (no response from service)");
            for (String family : expected) {
                limitations.add(
                        "counter " + family + " not exposed by service; no value inferred");
            }
        }
        if (expected.isEmpty()) {
            limitations.add("no business counters defined for this service; health only");
        }
        return new ObservedServiceFacts(
                entity.getExternalRef(),
                toPort(entity.getMetadata().get("port")),
                healthStatus,
                healthAvailable,
                Map.copyOf(counters),
                List.copyOf(limitations));
    }

    private static SystemEntity findManaged(List<SystemEntity> managed, String externalRef) {
        for (SystemEntity entity : managed) {
            if (externalRef.equals(entity.getExternalRef())) {
                return entity;
            }
        }
        throw new IllegalStateException("Collected facts for unmanaged entity: " + externalRef);
    }

    /**
     * Merges validated facts into the namespaced {@code runtime} metadata
     * key, preserving every other key. Returns true when the stored facts
     * (health + counters) differ — the observation timestamp itself always
     * advances so staleness is never hidden.
     */
    private static boolean applyFacts(
            SystemEntity entity, ObservedServiceFacts facts, Instant now) {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("managedBy", TelemetryConnector.CONNECTOR_ID);
        runtime.put("connectorVersion", TelemetryConnector.CONNECTOR_VERSION);
        runtime.put("observedAt", now.toString());
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("available", facts.healthAvailable());
        health.put("status", facts.healthStatus());
        runtime.put("health", health);
        Map<String, Object> counters = new LinkedHashMap<>(facts.counters());
        runtime.put("counters", counters);
        runtime.put("limitations", new ArrayList<>(facts.limitations()));

        boolean factsChanged = !sameFacts(entity.getMetadata().get(TelemetryConnector.RUNTIME_KEY), facts);
        Map<String, Object> metadata = new HashMap<>(entity.getMetadata());
        metadata.put(TelemetryConnector.RUNTIME_KEY, runtime);
        entity.setMetadata(metadata);
        return factsChanged;
    }

    @SuppressWarnings("unchecked")
    private static boolean sameFacts(Object stored, ObservedServiceFacts facts) {
        if (!(stored instanceof Map<?, ?> map)) {
            return false;
        }
        Object health = map.get("health");
        String storedStatus = null;
        boolean storedAvailable = false;
        if (health instanceof Map<?, ?> healthMap) {
            Object status = healthMap.get("status");
            storedStatus = status == null ? null : String.valueOf(status);
            storedAvailable = Boolean.TRUE.equals(healthMap.get("available"));
        }
        if (storedAvailable != facts.healthAvailable()) {
            return false;
        }
        if (storedStatus == null ? facts.healthStatus() != null
                : !storedStatus.equals(facts.healthStatus())) {
            return false;
        }
        Object counters = map.get("counters");
        Map<String, Long> storedCounters = new LinkedHashMap<>();
        if (counters instanceof Map<?, ?> countersMap) {
            for (Map.Entry<?, ?> entry : countersMap.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Number number) {
                    storedCounters.put(String.valueOf(entry.getKey()), number.longValue());
                } else {
                    return false;
                }
            }
        }
        return storedCounters.equals(new LinkedHashMap<>(facts.counters()));
    }
}
