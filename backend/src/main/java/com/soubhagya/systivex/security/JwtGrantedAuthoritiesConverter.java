package com.soubhagya.systivex.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class JwtGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final String roleClaim;

    private final Set<String> allowedRoles;

    public JwtGrantedAuthoritiesConverter(String roleClaim, Set<String> allowedRoles) {
        this.roleClaim = roleClaim == null ? "roles" : roleClaim;
        this.allowedRoles = allowedRoles == null ? Set.of() : Set.copyOf(allowedRoles);
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<String> roles = extractRoles(jwt.getClaims().get(roleClaim));
        Collection<GrantedAuthority> granted = new LinkedHashSet<>();
        for (String role : roles) {
            if (!allowedRoles.contains(role)) {
                continue;
            }
            granted.add(new SimpleGrantedAuthority("ROLE_" + role));
            if ("TWIN_ADMIN".equals(role)) {
                granted.add(new SimpleGrantedAuthority("ROLE_TWIN_READ"));
                granted.add(new SimpleGrantedAuthority("ROLE_TWIN_ANALYZE"));
                granted.add(new SimpleGrantedAuthority("ROLE_TWIN_OPERATOR"));
            }
        }
        return new ArrayList<>(granted);
    }

    private Set<String> extractRoles(Object claim) {
        Set<String> roles = new LinkedHashSet<>();
        if (claim instanceof String value) {
            roles.add(value);
        } else if (claim instanceof Collection<?> values) {
            for (Object value : values) {
                if (value instanceof String role) {
                    roles.add(role);
                }
            }
        } else if (claim instanceof Object[] values) {
            for (Object value : values) {
                if (value instanceof String role) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }
}