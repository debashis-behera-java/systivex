package com.soubhagya.systivex.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Allowlist-only role mapping: only configured roles become authorities,
 * malformed claims grant nothing, unknown values never grant access,
 * TWIN_ADMIN inherits READ, ANALYZE, and OPERATOR — never TWIN_APPROVER,
 * which a trusted issuer must grant explicitly.
 */
class JwtGrantedAuthoritiesConverterTest {

    private static final Set<String> ALLOWED =
            Set.of("TWIN_READ", "TWIN_ANALYZE", "TWIN_OPERATOR", "TWIN_ADMIN", "TWIN_APPROVER");

    private final JwtGrantedAuthoritiesConverter converter =
            new JwtGrantedAuthoritiesConverter("roles", ALLOWED);

    private static Jwt jwtWithClaim(Object rolesClaim) {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", "test-subject");
        if (rolesClaim != null) {
            claims.put("roles", rolesClaim);
        }
        return new Jwt("token", java.time.Instant.now(), java.time.Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"), claims);
    }

    private static Set<String> names(Collection<GrantedAuthority> authorities) {
        Set<String> names = new java.util.HashSet<>();
        for (GrantedAuthority authority : authorities) {
            names.add(authority.getAuthority());
        }
        return names;
    }

    @Test
    void allowlistedRolesMapToAuthorities() {
        assertThat(names(converter.convert(jwtWithClaim(List.of("TWIN_READ")))))
                .containsExactly("ROLE_TWIN_READ");
        assertThat(names(converter.convert(jwtWithClaim(List.of("TWIN_ANALYZE")))))
                .containsExactly("ROLE_TWIN_ANALYZE");
        assertThat(names(converter.convert(jwtWithClaim(List.of("TWIN_OPERATOR")))))
                .containsExactly("ROLE_TWIN_OPERATOR");
    }

    @Test
    void adminInheritsReadAnalyzeAndOperator() {
        assertThat(names(converter.convert(jwtWithClaim(List.of("TWIN_ADMIN")))))
                .containsExactlyInAnyOrder(
                        "ROLE_TWIN_ADMIN", "ROLE_TWIN_READ",
                        "ROLE_TWIN_ANALYZE", "ROLE_TWIN_OPERATOR");
    }

    @Test
    void approverIsGrantedOnlyWhenExplicitlyPresent() {
        assertThat(names(converter.convert(jwtWithClaim(List.of("TWIN_APPROVER")))))
                .containsExactly("ROLE_TWIN_APPROVER");
    }

    @Test
    void adminNeverImpliesApprover() {
        assertThat(names(converter.convert(jwtWithClaim(List.of("TWIN_ADMIN")))))
                .doesNotContain("ROLE_TWIN_APPROVER");
        assertThat(names(converter.convert(
                        jwtWithClaim(List.of("TWIN_ADMIN", "TWIN_APPROVER")))))
                .contains("ROLE_TWIN_APPROVER");
    }

    @Test
    void unknownRolesNeverGrantAccess() {
        assertThat(converter.convert(jwtWithClaim(List.of("SUPERUSER")))).isEmpty();
        assertThat(converter.convert(jwtWithClaim(List.of("TWIN_READ", "SUPERUSER"))))
                .hasSize(1);
    }

    @Test
    void malformedClaimsGrantNothing() {
        assertThat(converter.convert(jwtWithClaim(42))).isEmpty();
        assertThat(converter.convert(jwtWithClaim(Map.of("role", "TWIN_READ")))).isEmpty();
        assertThat(converter.convert(jwtWithClaim(null))).isEmpty();
        assertThat(converter.convert(jwtWithClaim(java.util.Arrays.asList(42, null)))).isEmpty();
        assertThat(converter.convert(new Jwt("t", java.time.Instant.now(),
                java.time.Instant.now().plusSeconds(60), Map.of("alg", "x"),
                Map.of("sub", "s"))))
                .isEmpty();
    }

    @Test
    void stringClaimAndCustomClaimName() {
        assertThat(names(converter.convert(jwtWithClaim("TWIN_READ"))))
                .containsExactly("ROLE_TWIN_READ");
        JwtGrantedAuthoritiesConverter custom =
                new JwtGrantedAuthoritiesConverter("groups", ALLOWED);
        assertThat(custom.convert(jwtWithClaim(List.of("TWIN_READ")))).isEmpty();
    }

    @Test
    void emptyAllowlistGrantsNothing() {
        JwtGrantedAuthoritiesConverter denyAll =
                new JwtGrantedAuthoritiesConverter("roles", Set.of());
        assertThat(denyAll.convert(jwtWithClaim(List.of("TWIN_ADMIN")))).isEmpty();
    }
}
