package com.soubhagya.systivex.security;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

@TestConfiguration
public class TestJwtIntegrationConfig {

    @Bean
    @Primary
    public JwtDecoder jwtDecoder(com.soubhagya.systivex.security.SystivexSecurityProperties properties) {
        // Test key discovery only: validators follow the production configuration
        // (issuer, audience, clock skew) so role/claim wiring stays realistic
        // without contacting a real identity provider.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(TestJwt.jwkSource()).build();
        org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<
                org.springframework.security.oauth2.jwt.Jwt> validator =
                new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                        new JwtIssuerValidator(properties.getIssuerUri()),
                        new JwtTimestampValidator(
                                java.time.Duration.ofSeconds(properties.getClockSkewSeconds())),
                        new com.soubhagya.systivex.security.SecurityConfig.AcceptedAudienceValidator(
                                properties.getAcceptedAudience()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    @Primary
    public JwtAuthenticationConverter jwtAuthenticationConverter(
            com.soubhagya.systivex.security.SystivexSecurityProperties properties) {
        // Follow the production configuration (role claim + allowlist) so the
        // integration suite exercises the configured authority mapping.
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                new com.soubhagya.systivex.security.JwtGrantedAuthoritiesConverter(
                        properties.getRoleClaim(), properties.getAllowedRoles()));
        return converter;
    }

    @Bean
    public BeanPostProcessor testRestTemplateBearerInterceptor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof org.springframework.boot.resttestclient.TestRestTemplate template) {
                    org.springframework.web.client.RestTemplate rt = template.getRestTemplate();
                    if (rt.getInterceptors().stream().noneMatch(i ->
                            i.getClass().getName().endsWith("TestJwtBearerInterceptor"))) {
                        rt.getInterceptors().add(new TestJwtBearerInterceptor());
                    }
                }
                return bean;
            }
        };
    }

    static final class TestJwtBearerInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public org.springframework.http.client.ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request,
                byte[] body,
                org.springframework.http.client.ClientHttpRequestExecution execution)
                throws java.io.IOException {
            request.getHeaders().add(HttpHeaders.AUTHORIZATION,
                    "Bearer " + TestJwt.token(java.util.List.of("TWIN_ADMIN")));
            return execution.execute(request, body);
        }
    }
}
