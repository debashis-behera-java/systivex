package com.soubhagya.systivex.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.twin.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 11 S1: proves the V1 → V2 upgrade path on an isolated scratch
 * database inside the same PostgreSQL container (never the shared
 * development database), and proves the governance constraints and the
 * genesis chain-head row on the migrated application schema.
 */
@SpringBootTest
class GovernanceMigrationTest extends AbstractPostgresIntegrationTest {

    private static final String SCRATCH_DB = "governance_upgrade";

    @Autowired private JdbcTemplate jdbc;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String datasourceUsername;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    @Test
    void upgradesV1ToV2WithTwinDataIntact() throws Exception {
        String adminUrl = datasourceUrl.replaceFirst("/[^/]*$", "/postgres");
        try (Connection admin = DriverManager.getConnection(adminUrl, datasourceUsername, datasourcePassword);
                Statement drop = admin.createStatement()) {
            drop.execute("DROP DATABASE IF EXISTS " + SCRATCH_DB + " WITH (FORCE)");
        }
        try (Connection admin = DriverManager.getConnection(adminUrl, datasourceUsername, datasourcePassword);
                Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE " + SCRATCH_DB);
        }
        String scratchUrl = datasourceUrl.replaceFirst("/[^/]*$", "/" + SCRATCH_DB);

        Flyway v1 =
                Flyway.configure()
                        .dataSource(scratchUrl, datasourceUsername, datasourcePassword)
                        .locations("classpath:db/migration")
                        .target("1")
                        .load();
        assertThat(v1.migrate().success).isTrue();

        UUID entityId = UUID.randomUUID();
        try (Connection data = DriverManager.getConnection(scratchUrl, datasourceUsername, datasourcePassword);
                Statement insert = data.createStatement()) {
            insert.execute(
                    "INSERT INTO system_entity (id, type, name, external_ref, environment, metadata)"
                            + " VALUES ('" + entityId + "', 'SERVICE', 'ledger', 'svc:ledger',"
                            + " 'prod', '{\"owner\":\"twin\"}')");
        }

        Flyway v2 =
                Flyway.configure()
                        .dataSource(scratchUrl, datasourceUsername, datasourcePassword)
                        .locations("classpath:db/migration")
                        .load();
        assertThat(v2.migrate().success).isTrue();
        assertThat(v2.info().current().getVersion().getVersion()).isEqualTo("2");

        List<Map<String, Object>> rows;
        try (Connection data = DriverManager.getConnection(scratchUrl, datasourceUsername, datasourcePassword);
                Statement select = data.createStatement()) {
            var results = select.executeQuery(
                    "SELECT id, type, name, external_ref, environment, metadata"
                            + " FROM system_entity WHERE id = '" + entityId + "'");
            var meta = results.getMetaData();
            assertThat(meta.getColumnCount()).isEqualTo(6);
            rows = new java.util.ArrayList<>();
            while (results.next()) {
                rows.add(Map.of(
                        "name", results.getString("name"),
                        "external_ref", results.getString("external_ref")));
            }
        }
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("name", "ledger").containsEntry("external_ref", "svc:ledger");

        try (Connection data = DriverManager.getConnection(scratchUrl, datasourceUsername, datasourcePassword);
                Statement tables = data.createStatement()) {
            var found = tables.executeQuery(
                    "SELECT table_name FROM information_schema.tables"
                            + " WHERE table_name IN"
                            + " ('proposal_revision', 'audit_event', 'audit_chain_head')");
            int count = 0;
            while (found.next()) {
                count++;
            }
            assertThat(count).isEqualTo(3);
        }

        try (Connection data = DriverManager.getConnection(scratchUrl, datasourceUsername, datasourcePassword);
                Statement head = data.createStatement()) {
            var genesis = head.executeQuery("SELECT last_seq, last_hash FROM audit_chain_head");
            assertThat(genesis.next()).isTrue();
            assertThat(genesis.getLong("last_seq")).isZero();
            assertThat(genesis.getString("last_hash")).isEqualTo("GENESIS");
            assertThat(genesis.next()).isFalse();
        }
    }

    @Test
    void governanceConstraintsPresent() {
        for (String name : List.of(
                "uq_proposal_revision_key_number",
                "chk_proposal_revision_number",
                "chk_proposal_revision_status",
                "uq_audit_event_seq",
                "chk_audit_event_seq",
                "chk_audit_event_action",
                "chk_audit_event_outcome",
                "fk_audit_event_revision",
                "chk_audit_chain_head_singleton")) {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM pg_constraint WHERE conname = '" + name + "'",
                            Integer.class))
                    .as("constraint %s", name)
                    .isOne();
        }
    }

    @Test
    void genesisHeadRowExistsExactlyOnce() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_chain_head", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT last_seq FROM audit_chain_head", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT last_hash FROM audit_chain_head", String.class))
                .isEqualTo("GENESIS");
    }
}
