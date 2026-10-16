package com.soubhagya.systivex.proposal.repository;

import com.soubhagya.systivex.proposal.model.AuditChainHead;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, Integer> {

    /**
     * Loads the single chain-head row under a pessimistic write lock so
     * later slices can allocate the next chain sequence number without
     * gaps or duplicates. S1 performs no appends; this only proves the
     * lockable read works.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from AuditChainHead h where h.id = 1")
    Optional<AuditChainHead> findHeadForUpdate();
}
