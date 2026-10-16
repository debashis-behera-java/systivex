package com.soubhagya.systivex.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.service.EntityNotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Validator unit tests: query validation (400/404 contracts), defaults,
 * deterministic ordering, and snapshot resolution — all without a database.
 */
class ImpactQueryValidatorTest {

    private static final UUID ID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ID_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static Map<UUID, SystemEntity> snapshot() {
        Map<UUID, SystemEntity> entities = new LinkedHashMap<>();
        entities.put(ID_A, entity(ID_A, "svc:a"));
        entities.put(ID_B, entity(ID_B, "svc:b"));
        return entities;
    }

    private static SystemEntity entity(UUID id, String ref) {
        SystemEntity entity =
                new SystemEntity(SystemEntityType.SERVICE, ref, ref, "dev", Map.of());
        try {
            var idField = SystemEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    @Test
    void nullBlankAndOversizedFromRejected() {
        Map<UUID, SystemEntity> entities = snapshot();
        assertThatThrownBy(() -> ImpactQueryValidator.parseSnapshotInputs(entities, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImpactQueryValidator.parseSnapshotInputs(entities, List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> ImpactQueryValidator.parseSnapshotInputs(
                                entities, List.of("  "), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> oversized = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            oversized.add("svc:a");
        }
        assertThatThrownBy(
                        () -> ImpactQueryValidator.parseSnapshotInputs(entities, oversized, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxDepthBoundsAndDefaults() {
        Map<UUID, SystemEntity> entities = snapshot();
        assertThatThrownBy(
                        () -> ImpactQueryValidator.parseSnapshotInputs(
                                entities, List.of("svc:a"), 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> ImpactQueryValidator.parseSnapshotInputs(
                                entities, List.of("svc:a"), 11, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ImpactQueryValidator.parseSnapshotInputs(entities, List.of("svc:a"), null, null)
                        .maxDepth())
                .isEqualTo(6);
    }

    @Test
    void unknownAndBlankIncludeTypesRejected() {
        Map<UUID, SystemEntity> entities = snapshot();
        assertThatThrownBy(
                        () -> ImpactQueryValidator.parseSnapshotInputs(
                                entities, List.of("svc:a"), null, List.of("NOPE")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> ImpactQueryValidator.parseSnapshotInputs(
                                entities, List.of("svc:a"), null, List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void includeTypesDefaultAndCaseInsensitive() {
        Map<UUID, SystemEntity> entities = snapshot();
        assertThat(ImpactQueryValidator.parseSnapshotInputs(entities, List.of("svc:a"), null, null)
                        .includeTypes())
                .containsExactlyInAnyOrder(
                        SystemEntityType.SERVICE,
                        SystemEntityType.API,
                        SystemEntityType.DATABASE,
                        SystemEntityType.TABLE);
        assertThat(ImpactQueryValidator.parseSnapshotInputs(
                                entities, List.of("svc:a"), null, List.of("service"))
                        .includeTypes())
                .containsExactly(SystemEntityType.SERVICE);
    }

    @Test
    void snapshotUnknownIdentifierIs404() {
        Map<UUID, SystemEntity> entities = snapshot();
        assertThatThrownBy(
                        () -> ImpactQueryValidator.parseSnapshotInputs(
                                entities, List.of("svc:ghost"), null, null))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(
                        () -> ImpactQueryValidator.parseSnapshotInputs(
                                entities, List.of(UUID.randomUUID().toString()), null, null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void snapshotUuidAndRefResolveIdentically() {
        Map<UUID, SystemEntity> entities = snapshot();
        ImpactQueryValidator.ParsedInputs byRef =
                ImpactQueryValidator.parseSnapshotInputs(entities, List.of("svc:a"), null, null);
        ImpactQueryValidator.ParsedInputs byUuid =
                ImpactQueryValidator.parseSnapshotInputs(
                        entities, List.of(ID_A.toString()), null, null);
        assertThat(byUuid.starts().get(0).getId()).isEqualTo(ID_A);
        assertThat(byRef.starts().get(0).getId()).isEqualTo(byUuid.starts().get(0).getId());
    }

    @Test
    void snapshotStartsSortedAndTrimmed() {
        Map<UUID, SystemEntity> entities = snapshot();
        ImpactQueryValidator.ParsedInputs reversed =
                ImpactQueryValidator.parseSnapshotInputs(
                        entities, List.of("  svc:b ", "svc:a"), null, null);
        assertThat(reversed.starts().stream().map(SystemEntity::getExternalRef).toList())
                .containsExactly("svc:a", "svc:b");
    }
}
