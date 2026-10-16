package com.soubhagya.systivex.proposal.repository;

import com.soubhagya.systivex.proposal.model.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findAllByOrderBySeqAsc();

    List<AuditEvent> findByProposalKeyAndRevisionNumberOrderBySeqAsc(UUID proposalKey, int revisionNumber);

    List<AuditEvent> findByProposalKeyOrderBySeqAsc(UUID proposalKey);
}
