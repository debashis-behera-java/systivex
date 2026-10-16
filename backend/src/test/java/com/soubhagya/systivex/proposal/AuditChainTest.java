package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.soubhagya.systivex.proposal.model.AuditAction;
import com.soubhagya.systivex.proposal.model.AuditEvent;
import com.soubhagya.systivex.proposal.model.AuditOutcome;
import com.soubhagya.systivex.proposal.model.ProposalRevision;
import com.soubhagya.systivex.proposal.model.ProposalStatus;
import com.soubhagya.systivex.proposal.repository.AuditChainHeadRepository;
import com.soubhagya.systivex.proposal.repository.AuditEventRepository;
import com.soubhagya.systivex.proposal.repository.ProposalRevisionRepository;
import com.soubhagya.systivex.proposal.service.AuditService;
import com.soubhagya.systivex.proposal.service.AuditVerification;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import com.soubhagya.systivex.twin.service.EntityNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 11 S2: proves the audit-chain mechanics against PostgreSQL —
 * genesis, sequential appends with correct linking, detection of every
 * tamper class, gap/reorder detection, atomic rollback between event
 * insert and head update, and unique contiguous sequences under
 * concurrent appends. Each test starts and ends with an empty chain, so
 * the shared database is left untouched.
 */
@SpringBootTest
class AuditChainTest extends AbstractPostgresIntegrationTest {

    private static final String OPS_HASH =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Autowired private AuditService audits;

    @Autowired private AuditEventRepository events;

    @Autowired private AuditChainHeadRepository head;

    @Autowired private ProposalRevisionRepository revisions;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    @AfterEach
    void resetChain() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM proposal_revision");
        jdbc.update("UPDATE audit_chain_head SET last_seq = 0, last_hash = 'GENESIS'");
    }

    private UUID revisionFixture() {
        UUID key = UUID.randomUUID();
        revisions.saveAndFlush(
                new ProposalRevision(
                        key,
                        1,
                        ProposalStatus.DRAFT,
                        "Retire ledger writer",
                        "[{\"type\":\"REMOVE_ENTITY\",\"entity\":\"e1\"}]",
                        OPS_HASH,
                        "https://issuer.example",
                        "proposer-subject"));
        return key;
    }

    private AuditService.AuditAppend appendFor(UUID key, String correlation) {
        return new AuditService.AuditAppend(
                "https://issuer.example",
                "actor-subject",
                AuditAction.CREATED,
                AuditOutcome.SUCCESS,
                key,
                1,
                OPS_HASH,
                null,
                "recorded for audit",
                correlation);
    }

    @Test
    void emptyChainVerifiesOk() {
        AuditVerification result = audits.verify();
        assertThat(result.status()).isEqualTo(AuditVerification.OK);
        assertThat(result.eventCount()).isZero();
    }

    @Test
    void singleAppendLinksGenesis() {
        UUID key = revisionFixture();
        AuditEvent event = audits.append(appendFor(key, "corr-1"));

        assertThat(event.getSeq()).isOne();
        assertThat(event.getPrevHash()).isEqualTo("GENESIS");
        AuditVerification result = audits.verify();
        assertThat(result.status()).isEqualTo(AuditVerification.OK);
        assertThat(result.eventCount()).isOne();
    }

    @Test
    void multipleAppendsAreContiguousAndVerify() {
        UUID key = revisionFixture();
        for (int i = 1; i <= 3; i++) {
            audits.append(appendFor(key, "corr-" + i));
        }
        List<AuditEvent> ordered = events.findAllByOrderBySeqAsc();
        assertThat(ordered).extracting(AuditEvent::getSeq).containsExactly(1L, 2L, 3L);
        for (int i = 1; i < ordered.size(); i++) {
            assertThat(ordered.get(i).getPrevHash()).isEqualTo(ordered.get(i - 1).getEventHash());
        }
        assertThat(audits.verify().status()).isEqualTo(AuditVerification.OK);
    }

    @Test
    void tamperedFieldIsDetected() {
        UUID key = revisionFixture();
        audits.append(appendFor(key, "corr-1"));
        jdbc.update("UPDATE audit_event SET reason = 'forged' WHERE seq = 1");

        AuditVerification result = audits.verify();
        assertThat(result.status()).isEqualTo(AuditVerification.BROKEN);
        assertThat(result.brokenAtSeq()).isEqualTo(1L);
    }

    @Test
    void tamperedEventHashIsDetected() {
        UUID key = revisionFixture();
        audits.append(appendFor(key, "corr-1"));
        jdbc.update("UPDATE audit_event SET event_hash = '" + OPS_HASH + "' WHERE seq = 1");

        assertThat(audits.verify().brokenAtSeq()).isEqualTo(1L);
    }

    @Test
    void tamperedPrevHashIsDetected() {
        UUID key = revisionFixture();
        audits.append(appendFor(key, "corr-1"));
        audits.append(appendFor(key, "corr-2"));
        jdbc.update("UPDATE audit_event SET prev_hash = '" + OPS_HASH + "' WHERE seq = 2");

        assertThat(audits.verify().brokenAtSeq()).isEqualTo(2L);
    }

    @Test
    void tamperedHeadIsDetected() {
        UUID key = revisionFixture();
        audits.append(appendFor(key, "corr-1"));
        jdbc.update("UPDATE audit_chain_head SET last_hash = '" + OPS_HASH + "'");

        AuditVerification result = audits.verify();
        assertThat(result.status()).isEqualTo(AuditVerification.BROKEN);
        assertThat(result.brokenAtSeq()).isEqualTo(1L);
    }

    @Test
    void missingEventIsDetected() {
        UUID key = revisionFixture();
        audits.append(appendFor(key, "corr-1"));
        audits.append(appendFor(key, "corr-2"));
        audits.append(appendFor(key, "corr-3"));
        jdbc.update("DELETE FROM audit_event WHERE seq = 2");

        AuditVerification result = audits.verify();
        assertThat(result.status()).isEqualTo(AuditVerification.BROKEN);
        assertThat(result.brokenAtSeq()).isEqualTo(3L);
    }

    @Test
    void reorderedSequencesAreDetected() {
        UUID key = revisionFixture();
        audits.append(appendFor(key, "corr-1"));
        audits.append(appendFor(key, "corr-2"));
        // Swap via a non-colliding placeholder: every intermediate state
        // respects the seq >= 1 CHECK and the seq uniqueness constraint.
        jdbc.update("UPDATE audit_event SET seq = 100 WHERE seq = 1");
        jdbc.update("UPDATE audit_event SET seq = 1 WHERE seq = 2");
        jdbc.update("UPDATE audit_event SET seq = 2 WHERE seq = 100");

        assertThat(audits.verify().status()).isEqualTo(AuditVerification.BROKEN);
    }

    @Test
    void failureBetweenInsertAndHeadUpdateRollsBackBoth() {
        UUID key = revisionFixture();
        AuditService failing =
                new AuditService(events, head, revisions) {
                    @Override
                    protected void afterEventStored(AuditEvent stored) {
                        throw new IllegalStateException("injected failure");
                    }
                };
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                        .execute(status -> failing.append(appendFor(key, "corr-1"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(events.count()).isZero();
        assertThat(head.findById(1).orElseThrow().getLastSeq()).isZero();
        assertThat(audits.verify().status()).isEqualTo(AuditVerification.OK);
    }

    @Test
    void concurrentAppendsYieldUniqueContiguousSequences() throws Exception {
        UUID key = revisionFixture();
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch ready = new CountDownLatch(writers);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<AuditEvent>> futures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                final String correlation = "concurrent-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate timed out");
                    }
                    return audits.append(appendFor(key, correlation));
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("writers never became ready");
            }
            start.countDown();
            for (Future<AuditEvent> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(events.findAllByOrderBySeqAsc())
                .extracting(AuditEvent::getSeq)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(audits.verify().status()).isEqualTo(AuditVerification.OK);
    }

    @Test
    void invalidInputsAreRejected() {
        UUID key = revisionFixture();
        assertThatThrownBy(() -> audits.append(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audits.append(new AuditService.AuditAppend(
                        "https://issuer.example", "  ", AuditAction.CREATED, AuditOutcome.SUCCESS,
                        key, 1, OPS_HASH, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audits.append(new AuditService.AuditAppend(
                        "https://issuer.example", "actor", AuditAction.CREATED, AuditOutcome.SUCCESS,
                        UUID.randomUUID(), 1, OPS_HASH, null, null, null)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(events.count()).isZero();
    }
}
