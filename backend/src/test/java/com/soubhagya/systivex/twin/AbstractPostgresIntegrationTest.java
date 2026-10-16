package com.soubhagya.systivex.twin;

import com.soubhagya.systivex.security.TestJwt;
import com.soubhagya.systivex.security.TestJwtIntegrationConfig;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One throwaway PostgreSQL per test JVM, shared by all integration tests.
 * A single container (instead of one per test class) keeps the suite stable
 * on machines where rapid container churn breaks port forwarding.
 * Nothing here touches the developer's local database.
 */
@Import(TestJwtIntegrationConfig.class)
public abstract class AbstractPostgresIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("systivex.security.jwt.issuer-uri", () -> TestJwt.ISSUER);
        registry.add("systivex.security.jwt.accepted-audience", () -> TestJwt.AUDIENCE);
        registry.add("systivex.security.jwt.role-claim", () -> "roles");
        registry.add("systivex.security.jwt.clock-skew-seconds", () -> 60L);
        registry.add("systivex.security.jwt.allowed-roles", () ->
                "TWIN_READ,TWIN_ANALYZE,TWIN_OPERATOR,TWIN_ADMIN,TWIN_APPROVER");
    }
}
