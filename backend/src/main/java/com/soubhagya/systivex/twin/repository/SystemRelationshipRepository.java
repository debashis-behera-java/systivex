package com.soubhagya.systivex.twin.repository;

import com.soubhagya.systivex.twin.model.SystemRelationship;
import com.soubhagya.systivex.twin.model.SystemRelationshipType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemRelationshipRepository extends JpaRepository<SystemRelationship, UUID> {

    @Override
    @EntityGraph(attributePaths = {"source", "target"})
    List<SystemRelationship> findAll();

    @Override
    @EntityGraph(attributePaths = {"source", "target"})
    Optional<SystemRelationship> findById(UUID id);

    boolean existsBySourceIdAndTargetIdAndRelationshipType(
            UUID sourceId, UUID targetId, SystemRelationshipType relationshipType);

    @EntityGraph(attributePaths = {"source", "target"})
    List<SystemRelationship> findBySourceId(UUID sourceId);

    @EntityGraph(attributePaths = {"source", "target"})
    List<SystemRelationship> findByTargetId(UUID targetId);
}
