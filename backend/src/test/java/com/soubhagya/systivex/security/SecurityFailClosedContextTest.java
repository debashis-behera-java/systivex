package com.soubhagya.systivex.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

/**
 * Proves fail-closed startup in an isolated context: a missing issuer and
 * {@code enabled=false} fail context startup instead of silently serving an
 * unauthenticated API. Uses only local property values; no real identity
 * provider is contacted. Scoped to configuration binding plus validation
 * (the fail-closed mechanism): decoder key fetching and the web-security
 * filter chain are covered by {@code ProductionJwtDecoderTest} and the
 * real-HTTP API suites instead.
 */
class SecurityFailClosedContextTest {

    @TestConfiguration
    static class PropsOnly {
        @Bean
        @ConfigurationProperties(prefix = "systivex.security.jwt")
        SystivexSecurityProperties systivexSecurityProperties() {
            return new SystivexSecurityProperties();
        }
    }

    private static ApplicationContextRunner runnerWith(String... props) {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(PropsOnly.class)
                .withPropertyValues(props);
    }

    private static Throwable startupFailure(String... props) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runnerWith(props).run(ctx -> failure.set(ctx.getStartupFailure()));
        return failure.get();
    }

    private static void assertFailsWithMessage(Throwable failure, String messagePart) {
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        assertThat(root)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(messagePart);
    }

    @Test
    void missingIssuerFailsStartup() {
        assertFailsWithMessage(startupFailure(), "issuer-uri is required");
        assertFailsWithMessage(
                startupFailure("systivex.security.jwt.issuer-uri="), "issuer-uri is required");
    }

    @Test
    void disablingSecurityFailsStartup() {
        assertFailsWithMessage(
                startupFailure(
                        "systivex.security.jwt.issuer-uri=https://issuer.example",
                        "systivex.security.jwt.enabled=false"),
                "must remain true");
    }

    @Test
    void validMinimalConfigurationStarts() {
        assertThat(startupFailure("systivex.security.jwt.issuer-uri=https://issuer.example"))
                .isNull();
    }
}
