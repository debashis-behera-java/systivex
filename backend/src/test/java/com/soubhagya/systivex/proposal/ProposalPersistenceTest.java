package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.soubhagya.systivex.proposal.model.AuditAction;
import com.soubhagya.systivex.proposal.model.AuditChainHead;
import com.soubhagya.systivex.proposal.model.AuditEvent;
import com.soubhagya.systivex.proposal.model.AuditOutcome;
import com.soubhagya.systivex.proposal.model.ProposalRevision;
import com.soubhagya.systivex.proposal.model.ProposalStatus;
import com.soubhagya.systivex.proposal.repository.AuditChainHeadRepository;
import com.soubhagya.systivex.proposal.repository.AuditEventRepository;
import com.soubhagya.systivex.proposal.repository.ProposalRevisionRepository;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 11 S1: proves the governance entities persist against PostgreSQL
 * (JSONB round-trip, optimistic-lock versioning, revision ordering, chain
 * sequence ordering) and that the database rejects duplicate revisions,
 * out-of-range revision numbers, unknown statuses, and orphan audit
 * events. No lifecycle or audit-append logic is exercised — persistence
 * only. Each test rolls back, leaving the shared database untouched.
 */
@SpringBootTest
@Transactional
class ProposalPersistenceTest extends AbstractPostgresIntegrationTest {

    private static final String OPS = "[{\"type\":\"REMOVE_ENTITY\",\"entity\":\"e1\"}]";

    private static final String OPS_HASH =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Autowired private ProposalRevisionRepository revisions;

    @Autowired private AuditEventRepository events;

    @Autowired private AuditChainHeadRepository head;

    @Autowired private EntityManager entityManager;

    @Autowired private JdbcTemplate jdbc;

    private static ProposalRevision draft(UUID key, int number) {
        return new ProposalRevision(
                key,
                number,
                ProposalStatus.DRAFT,
                "Retire ledger writer",
                OPS,
                OPS_HASH,
                "https://issuer.example",
                "proposer-subject");
    }

    @Test
    void persistsRevisionWithJsonbRoundTrip() throws Exception {
        ProposalRevision saved = revisions.saveAndFlush(draft(UUID.randomUUID(), 1));
        saved.setAssessment("{\"decision\":\"REVIEW\"}");
        saved.setAssessmentHash(OPS_HASH);
        saved.setPolicyDecision("REVIEW");
        saved.setPolicyVersion("policy-v1");
        saved.setRiskTaxonomy("risk-v1");
        revisions.saveAndFlush(saved);

        entityManager.clear();

        tools.jackson.databind.ObjectMapper json =
                tools.jackson.databind.json.JsonMapper.builder().build();
        ProposalRevision found = revisions.findById(saved.getId()).orElseThrow();
        // PostgreSQL jsonb normalizes insignificant whitespace on write, so
        // the round-trip is compared semantically rather than byte for byte.
        assertThat(json.readTree(found.getOperations())).isEqualTo(json.readTree(OPS));
        assertThat(json.readTree(found.getAssessment()))
                .isEqualTo(json.readTree("{\"decision\":\"REVIEW\"}"));
        assertThat(found.getPolicyDecision()).isEqualTo("REVIEW");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void jpaVersionStartsAtZeroAndIncrementsSeparatelyFromRevisionNumber() {
        ProposalRevision saved = revisions.saveAndFlush(draft(UUID.randomUUID(), 3));
        assertThat(saved.getRevisionNumber()).isEqualTo(3);
        assertThat(saved.getJpaVersion()).isZero();

        saved.setTitle("Retitled");
        revisions.saveAndFlush(saved);
        assertThat(saved.getRevisionNumber()).isEqualTo(3);
        assertThat(saved.getJpaVersion()).isEqualTo(1L);
    }

    @Test
    void revisionHistoryReadsInRevisionOrder() {
        UUID key = UUID.randomUUID();
        revisions.saveAndFlush(draft(key, 2));
        revisions.saveAndFlush(draft(key, 1));

        List<ProposalRevision> history = revisions.findByProposalKeyOrderByRevisionNumberAsc(key);
        assertThat(history).extracting(ProposalRevision::getRevisionNumber).containsExactly(1, 2);
        assertThat(revisions.findByProposalKeyAndRevisionNumber(key, 2)).isPresent();
        assertThat(revisions.findByProposalKeyAndRevisionNumber(key, 9)).isEmpty();
    }

    @Test
    void duplicateRevisionNumberViolatesUniqueConstraint() {
        UUID key = UUID.randomUUID();
        revisions.saveAndFlush(draft(key, 1));

        assertThatThrownBy(() -> revisions.saveAndFlush(draft(key, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidRevisionNumberViolatesCheckConstraint() {
        UUID key = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO proposal_revision (id, proposal_key,"
                                                + " revision_number, status, title, operations,"
                                                + " operations_hash, proposer_issuer,"
                                                + " proposer_subject) VALUES (?, ?, 0, 'DRAFT',"
                                                + " 't', '[]', ?, ?, ?)",
                                        UUID.randomUUID(),
                                        key,
                                        OPS_HASH,
                                        "https://issuer.example",
                                        "subject"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void unknownStatusViolatesCheckConstraint() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO proposal_revision (id, proposal_key,"
                                                + " revision_number, status, title, operations,"
                                                + " operations_hash, proposer_issuer,"
                                                + " proposer_subject) VALUES (?, ?, 1,"
                                                + " 'APPROVED_BY_ROBOT', 't', '[]', ?, ?, ?)",
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        OPS_HASH,
                                        "https://issuer.example",
                                        "subject"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void auditEventsReadInSequenceOrder() {
        UUID key = UUID.randomUUID();
        revisions.saveAndFlush(draft(key, 1));
        events.saveAndFlush(
                new AuditEvent(
                        2L,
                        "https://issuer.example",
                        "actor",
                        AuditAction.ASSESSED,
                        AuditOutcome.SUCCESS,
                        key,
                        1,
                        "GENESIS",
                        OPS_HASH));
        events.saveAndFlush(
                new AuditEvent(
                        1L,
                        "https://issuer.example",
                        "actor",
                        AuditAction.CREATED,
                        AuditOutcome.SUCCESS,
                        key,
                        1,
                        "GENESIS",
                        OPS_HASH));

        List<AuditEvent> ordered = events.findAllByOrderBySeqAsc();
        assertThat(ordered).extracting(AuditEvent::getSeq).isSorted();
        assertThat(events.findByProposalKeyAndRevisionNumberOrderBySeqAsc(key, 1))
                .extracting(AuditEvent::getAction)
                .containsExactly(AuditAction.CREATED, AuditAction.ASSESSED);
    }

    @Test
    void orphanAuditEventViolatesRevisionForeignKey() {
        assertThatThrownBy(
                        () ->
                                events.saveAndFlush(
                                        new AuditEvent(
                                                99L,
                                                "https://issuer.example",
                                                "actor",
                                                AuditAction.CREATED,
                                                AuditOutcome.SUCCESS,
                                                UUID.randomUUID(),
                                                1,
                                                "GENESIS",
                                                OPS_HASH)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void chainHeadLoadsForUpdate() {
        AuditChainHead locked = head.findHeadForUpdate().orElseThrow();
        assertThat(locked.getId()).isEqualTo(AuditChainHead.HEAD_ID);
        assertThat(locked.getLastSeq()).isZero();
        assertThat(locked.getLastHash()).isEqualTo(AuditChainHead.GENESIS_HASH);
        assertThat(head.count()).isOne();
    }
}
