package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.soubhagya.systivex.intelligence.ImpactAnalysisService;
import com.soubhagya.systivex.intelligence.SimulationService;
import com.soubhagya.systivex.policy.PolicyService;
import com.soubhagya.systivex.proposal.model.AuditEvent;
import com.soubhagya.systivex.proposal.model.ProposalRevision;
import com.soubhagya.systivex.proposal.model.ProposalStatus;
import com.soubhagya.systivex.proposal.repository.AuditChainHeadRepository;
import com.soubhagya.systivex.proposal.repository.AuditEventRepository;
import com.soubhagya.systivex.proposal.repository.ProposalRevisionRepository;
import com.soubhagya.systivex.proposal.service.AuditService;
import com.soubhagya.systivex.proposal.service.ProposalAmendmentService;
import com.soubhagya.systivex.proposal.service.ProposalAssessmentService;
import com.soubhagya.systivex.security.TestJwt;
import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import com.soubhagya.systivex.twin.repository.SystemRelationshipRepository;
import com.soubhagya.systivex.proposal.api.CreateProposalRequest;
import com.soubhagya.systivex.proposal.api.SubmitRequest;
import com.soubhagya.systivex.proposal.service.ProposalCanonical;
import com.soubhagya.systivex.intelligence.SimulationOperation;
import com.soubhagya.systivex.intelligence.SimulationOperationType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 11 S4: proves assess, submit, and amend each roll back fully
 * when the audit append fails. Services are constructed manually with a
 * failing audit seam inside a programmatic transaction — no special
 * application context required.
 */
@SpringBootTest
class ProposalMutationRollbackTest extends AbstractPostgresIntegrationTest {

    private static final String ISSUER = TestJwt.ISSUER;

    @Autowired private ProposalRevisionRepository revisions;

    @Autowired private AuditEventRepository events;

    @Autowired private AuditChainHeadRepository head;

    @Autowired private SystemEntityRepository entities;

    @Autowired private SystemRelationshipRepository relationships;

    @Autowired private ImpactAnalysisService impactService;

    @Autowired private SimulationService simulationService;

    @Autowired private PolicyService policyService;

    @Autowired private PlatformTransactionManager transactions;

    @Autowired private JdbcTemplate jdbc;

    private String prefix;
    private String refC;

    @BeforeEach
    void setup() {
        prefix = "sec-s4r-" + UUID.randomUUID().toString().substring(0, 8);
        refC = prefix + ":svc-c";
        jdbc.update(
                "INSERT INTO system_entity (id, type, name, external_ref, environment, metadata)"
                        + " VALUES (?, 'SERVICE', 'Gamma', ?, 'test', '{}')",
                UUID.randomUUID(), refC);
        resetGovernance();
    }

    @AfterEach
    void clean() {
        resetGovernance();
        jdbc.update("DELETE FROM system_entity WHERE external_ref LIKE '" + prefix + ":%'");
    }

    private void resetGovernance() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM proposal_revision");
        jdbc.update("UPDATE audit_chain_head SET last_seq = 0, last_hash = 'GENESIS'");
    }

    private ProposalAssessmentService failingAssessments() {
        AuditService failing =
                new AuditService(events, head, revisions) {
                    @Override
                    protected void afterEventStored(AuditEvent stored) {
                        throw new IllegalStateException("injected audit failure");
                    }
                };
        return new ProposalAssessmentService(
                revisions, failing, entities, relationships,
                impactService, simulationService, policyService);
    }

    private ProposalAmendmentService failingAmendments() {
        AuditService failing =
                new AuditService(events, head, revisions) {
                    @Override
                    protected void afterEventStored(AuditEvent stored) {
                        throw new IllegalStateException("injected audit failure");
                    }
                };
        return new ProposalAmendmentService(revisions, failing, entities, relationships);
    }

    private UUID draftRevision() {
        String entityId = jdbc.queryForObject(
                "SELECT id FROM system_entity WHERE external_ref = ?", String.class, refC);
        String ops = "{\"removedEntities\":[\"" + entityId
                + "\"],\"addedRelationships\":[],\"removedRelationships\":[]}";
        ProposalRevision revision = new ProposalRevision(
                UUID.randomUUID(),
                1,
                ProposalStatus.DRAFT,
                "Change",
                ops,
                ProposalCanonical.sha256Hex(ProposalCanonical.normalize(ops)),
                ISSUER,
                "subject");
        return new TransactionTemplate(transactions)
                .execute(status -> revisions.saveAndFlush(revision).getProposalKey());
    }

    private List<SimulationOperation> removeGammaOp() {
        var op = new SimulationOperation(SimulationOperationType.REMOVE_ENTITY, refC, null, null, null);
        return List.of(op);
    }

    @Test
    void assessRollbackKeepsDraftUnassessed() {
        UUID key = draftRevision();
        ProposalAssessmentService assessments = failingAssessments();
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                        .execute(status -> assessments.assess(key, ISSUER, "subject")))
                .isInstanceOf(IllegalStateException.class);

        ProposalRevision stored = revisions.findByProposalKeyAndRevisionNumber(key, 1).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ProposalStatus.DRAFT);
        assertThat(stored.getAssessment()).isNull();
        assertThat(events.count()).isZero();
    }

    @Test
    void submitRollbackKeepsAssessed() {
        UUID key = draftRevision();
        String assessment = "{\"decision\":\"REVIEW\"}";
        jdbc.update(
                "UPDATE proposal_revision SET status = 'ASSESSED', assessment = ?::jsonb,"
                        + " assessment_hash = ?, policy_decision = 'REVIEW', policy_version ="
                        + " 'policy-v1', risk_taxonomy = 'risk-v1', twin_fingerprint = ?,"
                        + " snapshot_observed_at = now(),"
                        + " expires_at = now() + interval '1 day' WHERE proposal_key = ?",
                assessment, ProposalCanonical.sha256Hex(assessment),
                ProposalCanonical.sha256Hex("fingerprint"), key);
        String opsHash = revisions.findByProposalKeyAndRevisionNumber(key, 1).orElseThrow()
                .getOperationsHash();
        ProposalAssessmentService assessments = failingAssessments();
        SubmitRequest submit =
                new SubmitRequest(1, opsHash, ProposalCanonical.sha256Hex(assessment));
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                        .execute(status -> assessments.submit(key, submit, ISSUER, "subject")))
                .isInstanceOf(IllegalStateException.class);

        ProposalRevision stored = revisions.findByProposalKeyAndRevisionNumber(key, 1).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ProposalStatus.ASSESSED);
        assertThat(events.count()).isZero();
    }

    @Test
    void amendRollbackLeavesSingleDraft() {
        UUID key = draftRevision();
        ProposalAmendmentService amendments = failingAmendments();
        CreateProposalRequest amend = new CreateProposalRequest("Amended", null, removeGammaOp());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(
                        status -> amendments.amend(key, ISSUER, "subject", false, amend)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(revisions.findByProposalKeyOrderByRevisionNumberAsc(key)).hasSize(1);
        assertThat(revisions.findByProposalKeyAndRevisionNumber(key, 1).orElseThrow().getStatus())
                .isEqualTo(ProposalStatus.DRAFT);
        assertThat(events.count()).isZero();
    }
}
