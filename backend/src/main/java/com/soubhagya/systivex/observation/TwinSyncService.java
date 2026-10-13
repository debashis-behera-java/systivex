package com.soubhagya.systivex.observation;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import com.soubhagya.systivex.twin.repository.SystemRelationshipRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 4 synchronization: persists a complete {@link DiscoveryResult} into
 * the twin. Discovery runs first and validates fully; only a complete
 * result reaches the writes below, and the whole method is one transaction,
 * so an invalid root, a discovery failure, an ownership conflict, or a
 * database error can never leave a partial graph.
 *
 * <p>Ownership: rows whose {@code externalRef} the connector manages are
 * created or refreshed; rows it does not own (manual records, or any row
 * whose metadata lacks the connector marker) are never modified or
 * deleted. A colliding identity aborts the run with {@link
 * OwnershipConflictException}.
 */
@Service
public class TwinSyncService {

    private final TargetRepositoryScanner scanner;
    private final SystemEntityRepository entities;
    private final SystemRelationshipRepository relationships;

    public TwinSyncService(
            TargetRepositoryScanner scanner,
            SystemEntityRepository entities,
            SystemRelationshipRepository relationships) {
        this.scanner = scanner;
        this.entities = entities;
        this.relationships = relationships;
    }

    @Transactional
    public SyncResult synchronize(Path root) {
        DiscoveryResult discovered = scanner.scan(root);

        int entitiesCreated = 0;
        int entitiesUpdated = 0;
        int entitiesUnchanged = 0;
        Map<String, SystemEntity> byRef = new HashMap<>();
        for (DiscoveredEntity spec : discovered.entities()) {
            SystemEntity existing = entities.findByExternalRef(spec.externalRef()).orElse(null);
            if (existing == null) {
                SystemEntity created =
                        new SystemEntity(
                                spec.type(),
                                spec.name(),
                                spec.externalRef(),
                                spec.environment(),
                                new HashMap<>(spec.metadata()));
                entities.save(created);
                byRef.put(spec.externalRef(), created);
                entitiesCreated++;
            } else if (isConnectorOwned(existing)) {
                if (isSame(existing, spec)) {
                    byRef.put(spec.externalRef(), existing);
                    entitiesUnchanged++;
                } else {
                    existing.setType(spec.type());
                    existing.setName(spec.name());
                    existing.setEnvironment(spec.environment());
                    // Phase 6 runtime annotations live under a namespaced key
                    // and survive structural re-syncs; they are never part of
                    // the repository-connector fact set.
                    Map<String, Object> merged = new HashMap<>(spec.metadata());
                    Object runtime =
                            existing.getMetadata() == null
                                    ? null
                                    : existing.getMetadata().get(
                                            com.soubhagya.systivex.telemetry.TelemetryConnector
                                                    .RUNTIME_KEY);
                    if (runtime != null) {
                        merged.put(
                                com.soubhagya.systivex.telemetry.TelemetryConnector.RUNTIME_KEY,
                                runtime);
                    }
                    existing.setMetadata(merged);
                    entities.save(existing);
                    byRef.put(spec.externalRef(), existing);
                    entitiesUpdated++;
                }
            } else {
                throw new OwnershipConflictException(
                        "Twin entity " + spec.externalRef()
                                + " already exists and is not connector-managed; "
                                + "rename it or delete it before synchronizing");
            }
        }

        int relationshipsCreated = 0;
        int relationshipsUnchanged = 0;
        for (DiscoveredRelationship spec : discovered.relationships()) {
            SystemEntity source = byRef.get(spec.sourceRef());
            SystemEntity target = byRef.get(spec.targetRef());
            if (source == null || target == null) {
                throw new IllegalStateException(
                        "Discovery produced an edge with an unresolved endpoint: "
                                + spec.sourceRef() + " -> " + spec.targetRef());
            }
            if (relationships.existsBySourceIdAndTargetIdAndRelationshipType(
                    source.getId(), target.getId(), spec.type())) {
                relationshipsUnchanged++;
            } else {
                relationships.save(new SystemRelationship(source, target, spec.type()));
                relationshipsCreated++;
            }
        }

        return new SyncResult(
                entitiesCreated,
                entitiesUpdated,
                entitiesUnchanged,
                relationshipsCreated,
                relationshipsUnchanged,
                root.toAbsolutePath().normalize().toString(),
                GitRevision.readShortHead(root).orElse(null),
                Instant.now());
    }

    private static boolean isConnectorOwned(SystemEntity entity) {
        return TargetRepositoryScanner.CONNECTOR_ID.equals(
                entity.getMetadata() == null ? null : entity.getMetadata().get("managedBy"));
    }

    private static boolean isSame(SystemEntity existing, DiscoveredEntity spec) {
        // Runtime annotations are excluded: a re-sync over unchanged
        // repository facts reports unchanged even when Phase 6 observations
        // have annotated the row since the last sync.
        Map<String, Object> stored =
                existing.getMetadata() == null ? Map.of() : new HashMap<>(existing.getMetadata());
        stored.remove(com.soubhagya.systivex.telemetry.TelemetryConnector.RUNTIME_KEY);
        return existing.getType() == spec.type()
                && Objects.equals(existing.getName(), spec.name())
                && Objects.equals(existing.getEnvironment(), spec.environment())
                && Objects.equals(stored, spec.metadata());
    }
}
