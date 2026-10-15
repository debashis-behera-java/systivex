package com.soubhagya.systivex.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Verifies the actual production authority-converter wiring
 * ({@code SecurityConfig.jwtGrantedAuthoritiesConverter} +
 * {@code jwtAuthenticationConverter}) honors a custom role-claim name: the
 * configured claim grants authority, the default {@code roles} claim alone
 * does not, and unknown/malformed values grant nothing. Assertions focus on
 * {@code ROLE_} authorities: Spring Security also adds non-role factor
 * authorities (e.g. {@code FACTOR_BEARER}), which never satisfy
 * {@code hasRole} checks.
 */
class ProductionAuthorityConverterWiringTest {

    private static final Set<String> ALLOWED =
            Set.of("TWIN_READ", "TWIN_ANALYZE", "TWIN_OPERATOR", "TWIN_ADMIN");

    private static JwtAuthenticationConverter productionConverter(String roleClaim) {
        SystivexSecurityProperties properties = new SystivexSecurityProperties();
        properties.setIssuerUri("https://issuer.example");
        properties.setRoleClaim(roleClaim);
        properties.setAllowedRoles(ALLOWED);
        properties.validate();
        SecurityConfig config = new SecurityConfig();
        return config.jwtAuthenticationConverter(
                config.jwtGrantedAuthoritiesConverter(properties));
    }

    private static Jwt jwtWithClaims(Map<String, Object> claims) {
        Map<String, Object> all = new java.util.HashMap<>(claims);
        all.putIfAbsent("sub", "test-subject");
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"), all);
    }

    private static Set<String> roles(Authentication authentication) {
        Set<String> names = new java.util.HashSet<>();
        authentication.getAuthorities().forEach(a -> names.add(a.getAuthority()));
        names.removeIf(name -> !name.startsWith("ROLE_"));
        return names;
    }

    @Test
    void configuredClaimGrantsAuthority() {
        JwtAuthenticationConverter converter = productionConverter("groups");
        Jwt jwt = jwtWithClaims(Map.of("groups", List.of("TWIN_READ")));
        assertThat(roles(converter.convert(jwt))).containsExactly("ROLE_TWIN_READ");
    }

    @Test
    void defaultRolesClaimAloneGrantsNothingWhenConfiguredClaimDiffers() {
        JwtAuthenticationConverter converter = productionConverter("groups");
        Jwt jwt = jwtWithClaims(Map.of("roles", List.of("TWIN_READ")));
        assertThat(roles(converter.convert(jwt))).isEmpty();
    }

    @Test
    void unknownAndMalformedValuesNeverGrantAccessThroughProductionWiring() {
        JwtAuthenticationConverter converter = productionConverter("groups");
        assertThat(roles(converter.convert(
                        jwtWithClaims(Map.of("groups", List.of("SUPERUSER"))))))
                .isEmpty();
        assertThat(roles(converter.convert(jwtWithClaims(Map.of("groups", 42)))))
                .isEmpty();
        assertThat(roles(converter.convert(jwtWithClaims(Map.of("sub", "only-subject")))))
                .isEmpty();
    }
}
