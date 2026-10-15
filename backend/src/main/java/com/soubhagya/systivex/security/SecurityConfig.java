package com.soubhagya.systivex.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

@Configuration
public class SecurityConfig {

    /**
     * Phase 10 control-plane authentication and authorization.
     *
     * <p>OAuth2 resource server with JWT bearer tokens only: stateless sessions,
     * CSRF disabled for this token-only API, no CORS rules, no Basic auth, no
     * local passwords. The trusted issuer is mandatory; a custom JWK Set URL
     * only overrides key discovery and never disables issuer validation.
     * Role mapping is allowlist-only with TWIN_ADMIN inheriting READ, ANALYZE,
     * and OPERATOR. Sync/observation loopback guards stay authoritative behind
     * the OPERATOR role. Security errors are fixed safe messages: never tokens,
     * headers, secrets, or JWT claims.
     */

    @Bean
    @ConfigurationProperties(prefix = "systivex.security.jwt")
    public SystivexSecurityProperties systivexSecurityProperties() {
        return new SystivexSecurityProperties();
    }

    @Bean
    public JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter(
            SystivexSecurityProperties properties) {
        return new JwtGrantedAuthoritiesConverter(properties.getRoleClaim(),
                properties.getAllowedRoles());
    }

    @Bean
    @ConditionalOnMissingBean(JwtAuthenticationConverter.class)
    public JwtAuthenticationConverter jwtAuthenticationConverter(
            JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(SystivexSecurityProperties properties) {
        String jwkSetUri = properties.getJwkSetUri();
        NimbusJwtDecoder decoder;
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            // Custom key discovery only: issuer validation below still applies.
            decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        } else {
            decoder = (NimbusJwtDecoder)
                    JwtDecoders.fromIssuerLocation(properties.getIssuerUri());
        }
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtIssuerValidator(properties.getIssuerUri()));
        validators.add(new JwtTimestampValidator(Duration.ofSeconds(properties.getClockSkewSeconds())));
        validators.add(new AcceptedAudienceValidator(properties.getAcceptedAudience()));
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> writeSecurityErrorResponse(
                response, HttpStatus.UNAUTHORIZED, "Authentication required",
                request.getRequestURI());
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> writeSecurityErrorResponse(
                response, HttpStatus.FORBIDDEN, "Access denied", request.getRequestURI());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter authenticationConverter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/error").permitAll()
                                        .requestMatchers(HttpMethod.GET, "/actuator/health",
                                                "/actuator/health/**").permitAll()
                                        .requestMatchers("/actuator/prometheus").hasRole("TWIN_READ")
                                        .requestMatchers("/actuator/**").denyAll()
                                        .requestMatchers(HttpMethod.GET, "/api/v1/twin/entities",
                                                "/api/v1/twin/entities/*").hasRole("TWIN_READ")
                                        .requestMatchers(HttpMethod.GET, "/api/v1/twin/relationships",
                                                "/api/v1/twin/relationships/*").hasRole("TWIN_READ")
                                        .requestMatchers(HttpMethod.GET, "/api/v1/twin/impact", "/api/v1/twin/impact/**",
                                                "/api/v1/twin/risk", "/api/v1/twin/risk/**").hasRole("TWIN_READ")
                                        .requestMatchers(HttpMethod.POST, "/api/v1/twin/sync",
                                                "/api/v1/twin/observations").hasRole("TWIN_OPERATOR")
                                        .requestMatchers(HttpMethod.POST, "/api/v1/twin/entities").hasRole("TWIN_ADMIN")
                                        .requestMatchers(HttpMethod.POST, "/api/v1/twin/relationships").hasRole("TWIN_ADMIN")
                                        .requestMatchers(HttpMethod.POST, "/api/v1/twin/simulations",
                                                "/api/v1/twin/policy-evaluations").hasRole("TWIN_ANALYZE")
                                        .anyRequest().denyAll())
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2
                                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter))
                                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(
                        handling -> handling.accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    private static void writeSecurityErrorResponse(
            HttpServletResponse response,
            HttpStatus status,
            String detail,
            String path)
            throws IOException {
        String json = "{\"status\":" + status.value()
                + ",\"error\":\"" + reason(status) + "\""
                + ",\"message\":\"" + escape(detail) + "\""
                + ",\"path\":\"" + escape(path) + "\""
                + ",\"fieldErrors\":null}";
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json);
    }

    private static String reason(HttpStatus status) {
        return status.getReasonPhrase();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static class AcceptedAudienceValidator implements OAuth2TokenValidator<Jwt> {
        private final String expectedAudience;

        AcceptedAudienceValidator(String expectedAudience) {
            this.expectedAudience = expectedAudience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            if (jwt.getAudience() != null && jwt.getAudience().contains(expectedAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        }
    }
}
