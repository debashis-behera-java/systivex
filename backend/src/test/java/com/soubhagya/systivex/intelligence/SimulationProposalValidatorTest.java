package com.soubhagya.systivex.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.soubhagya.systivex.twin.model.SystemRelationshipType;
import com.soubhagya.systivex.twin.service.EntityNotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure proposal validator
 * ({@link SimulationProposalValidator}): the complete validation matrix —
 * shape 400s before resolution 404s before conflicts 409 — plus canonical
 * (alias-proof) duplicate detection and deterministic first-violation
 * ordering. No database, no Spring.
 */
class SimulationProposalValidatorTest {

    private static final UUID ROOT = UUID.randomUUID();
    private static final UUID SVC_A = UUID.randomUUID();
    private static final UUID SVC_B = UUID.randomUUID();
    private static final UUID SVC_C = UUID.randomUUID();

    private final Map<String, UUID> refs = new HashMap<>(Map.of(
            "root", ROOT,
            "svc-a", SVC_A,
            "svc-b", SVC_B,
            "svc-c", SVC_C,
            SVC_A.toString(), SVC_A,
            SVC_B.toString(), SVC_B));

    private final SimulationProposalValidator.EntityResolver resolver =
            raw -> {
                UUID id = refs.get(raw);
                if (id == null) {
                    throw new EntityNotFoundException("SystemEntity not found: " + raw);
                }
                return id;
            };

    private static String edgeKey(UUID source, UUID target, SystemRelationshipType type) {
        return source + "|" + target + "|" + type.name();
    }

    private final Set<String> existing =
            Set.of(edgeKey(SVC_A, SVC_B, SystemRelationshipType.CALLS));

    private SimulationProposalValidator.ResolvedProposal validate(
            List<SimulationOperation> operations) {
        return SimulationProposalValidator.validate(operations, Set.of(ROOT), existing, resolver);
    }

    private static SimulationOperation removeEntity(String entity) {
        return new SimulationOperation(
                SimulationOperationType.REMOVE_ENTITY, entity, null, null, null);
    }

    private static SimulationOperation addEdge(
            String source, String target, SystemRelationshipType type) {
        return new SimulationOperation(
                SimulationOperationType.ADD_RELATIONSHIP, null, source, target, type);
    }

    private static SimulationOperation dropEdge(
            String source, String target, SystemRelationshipType type) {
        return new SimulationOperation(
                SimulationOperationType.REMOVE_RELATIONSHIP, null, source, target, type);
    }

    @Test
    void nullProposalValidatesToEmpty() {
        SimulationProposalValidator.ResolvedProposal proposal = validate(null);

        assertThat(proposal.removedEntityIds()).isEmpty();
        assertThat(proposal.addedEdges()).isEmpty();
        assertThat(proposal.removedEdges()).isEmpty();
    }

    @Test
    void validMixedProposalResolvesCanonically() {
        SimulationProposalValidator.ResolvedProposal proposal =
                validate(List.of(
                        removeEntity("svc-c"),
                        addEdge("svc-a", "svc-b", SystemRelationshipType.DEPENDS_ON),
                        dropEdge("svc-a", "svc-b", SystemRelationshipType.CALLS)));

        assertThat(proposal.removedEntityIds()).containsExactly(SVC_C);
        assertThat(proposal.addedEdges())
                .containsExactly(
                        new SimulationProposalValidator.ResolvedEdge(
                                SVC_A, SVC_B, SystemRelationshipType.DEPENDS_ON));
        assertThat(proposal.removedEdges())
                .containsExactly(
                        new SimulationProposalValidator.ResolvedEdge(
                                SVC_A, SVC_B, SystemRelationshipType.CALLS));
    }

    @Test
    void nullOperationFails400() {
        List<SimulationOperation> ops = new java.util.ArrayList<>();
        ops.add(null);

        assertThatThrownBy(() -> validate(ops))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type is required");
    }

    @Test
    void nullTypeFails400() {
        assertThatThrownBy(
                        () -> validate(List.of(
                                new SimulationOperation(null, "svc-a", null, null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type is required");
    }

    @Test
    void blankEntityFails400() {
        assertThatThrownBy(() -> validate(List.of(removeEntity("   "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entity is required");
    }

    @Test
    void blankSourceOrTargetFails400() {
        assertThatThrownBy(
                        () -> validate(List.of(addEdge("  ", "svc-b", SystemRelationshipType.CALLS))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source and target are required");
        assertThatThrownBy(
                        () -> validate(List.of(dropEdge("svc-a", null, SystemRelationshipType.CALLS))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source and target are required");
    }

    @Test
    void missingRelationshipTypeFails400() {
        assertThatThrownBy(() -> validate(List.of(addEdge("svc-a", "svc-b", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relationshipType is required");
    }

    @Test
    void unknownIdentifierFails404() {
        assertThatThrownBy(() -> validate(List.of(removeEntity("svc-missing"))))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(
                        () -> validate(List.of(
                                addEdge("svc-a", "svc-missing", SystemRelationshipType.CALLS))))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void shapeCheckedBeforeResolution() {
        // A blank entity (400) is reported even though another operation
        // references an unknown identifier (404): phase order is fixed.
        assertThatThrownBy(() -> validate(List.of(
                        addEdge("svc-a", "svc-missing", SystemRelationshipType.CALLS),
                        removeEntity("  "))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolutionCheckedBeforeConflicts() {
        // Unknown identifier (404) beats the root-removal conflict (409)
        // when resolution runs first — even in later request positions.
        assertThatThrownBy(() -> validate(List.of(
                        removeEntity("root"),
                        removeEntity("svc-missing"))))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void removingRootFails409() {
        assertThatThrownBy(() -> validate(List.of(removeEntity("root"))))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("analysis root");
    }

    @Test
    void duplicateExactOperationsFail409() {
        assertThatThrownBy(() -> validate(List.of(
                        dropEdge("svc-a", "svc-b", SystemRelationshipType.CALLS),
                        dropEdge("svc-a", "svc-b", SystemRelationshipType.CALLS))))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("Duplicate operation");
    }

    @Test
    void duplicateAcrossUuidAndRefAliasesFails409() {
        // Same canonical edge via UUID strings and external references.
        assertThatThrownBy(() -> validate(List.of(
                        dropEdge("svc-a", "svc-b", SystemRelationshipType.CALLS),
                        dropEdge(
                                SVC_A.toString(),
                                SVC_B.toString(),
                                SystemRelationshipType.CALLS))))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("Duplicate operation");
    }

    @Test
    void duplicateEntityRemovalsFail409() {
        assertThatThrownBy(
                        () -> validate(List.of(removeEntity("svc-c"), removeEntity("svc-c"))))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("Duplicate operation");
    }

    @Test
    void contradictoryAddAndRemoveFails409() {
        assertThatThrownBy(() -> validate(List.of(
                        addEdge("svc-b", "svc-c", SystemRelationshipType.CALLS),
                        dropEdge("svc-b", "svc-c", SystemRelationshipType.CALLS))))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("Contradictory");
    }

    @Test
    void addingExistingEdgeFails409() {
        assertThatThrownBy(() -> validate(List.of(
                        addEdge("svc-a", "svc-b", SystemRelationshipType.CALLS))))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void removingMissingEdgeFails409() {
        assertThatThrownBy(() -> validate(List.of(
                        dropEdge("svc-b", "svc-c", SystemRelationshipType.CALLS))))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void edgeIncidentToRemovedEntityFails409() {
        assertThatThrownBy(() -> validate(List.of(
                        removeEntity("svc-c"),
                        addEdge("svc-a", "svc-c", SystemRelationshipType.CALLS))))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("removed by this proposal");
        assertThatThrownBy(() -> validate(List.of(
                        removeEntity("svc-a"),
                        dropEdge("svc-a", "svc-b", SystemRelationshipType.CALLS))))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("removed by this proposal");
    }

    @Test
    void rootRemovalBeatsLaterDuplicatesInOrder() {
        // Conflict phases run in listed order: root removal (phase a) wins
        // over an exact duplicate (phase b) elsewhere in the request.
        assertThatThrownBy(() -> validate(List.of(
                        dropEdge("svc-a", "svc-b", SystemRelationshipType.CALLS),
                        dropEdge("svc-a", "svc-b", SystemRelationshipType.CALLS),
                        removeEntity("root"))))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("analysis root");
    }
}
