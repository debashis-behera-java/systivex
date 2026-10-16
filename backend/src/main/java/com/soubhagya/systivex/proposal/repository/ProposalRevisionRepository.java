package com.soubhagya.systivex.proposal.repository;

import com.soubhagya.systivex.proposal.model.ProposalRevision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProposalRevisionRepository extends JpaRepository<ProposalRevision, UUID> {

    List<ProposalRevision> findByProposalKeyOrderByRevisionNumberAsc(UUID proposalKey);

    Optional<ProposalRevision> findByProposalKeyAndRevisionNumber(UUID proposalKey, int revisionNumber);

    /**
     * Latest revision per proposal, newest first. Later slices reuse this
     * for amendment and decision guards.
     */
    @Query(
            value =
                    "select r from ProposalRevision r where r.revisionNumber = "
                            + "(select max(r2.revisionNumber) from ProposalRevision r2 "
                            + "where r2.proposalKey = r.proposalKey) order by r.createdAt desc",
            countQuery =
                    "select count(distinct r.proposalKey) from ProposalRevision r")
    Page<ProposalRevision> findLatestRevisions(Pageable pageable);
}
