package com.soubhagya.systivex.security;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.validation.annotation.Validated;

@Validated
public class SystivexSecurityProperties {

    private boolean enabled = true;

    private String issuerUri;

    private String jwkSetUri;

    private String acceptedAudience = "systivex";

    private String roleClaim = "roles";

    private long clockSkewSeconds = 60;

    private Set<String> allowedRoles =
            new LinkedHashSet<>(
                    Set.of("TWIN_READ", "TWIN_ANALYZE", "TWIN_OPERATOR", "TWIN_ADMIN",
                            "TWIN_APPROVER"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = trim(issuerUri);
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = trim(jwkSetUri);
    }

    public String getAcceptedAudience() {
        return acceptedAudience;
    }

    public void setAcceptedAudience(String acceptedAudience) {
        this.acceptedAudience = trim(acceptedAudience);
    }

    public String getRoleClaim() {
        return roleClaim;
    }

    public void setRoleClaim(String roleClaim) {
        this.roleClaim = trim(roleClaim);
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public Set<String> getAllowedRoles() {
        return allowedRoles;
    }

    public void setAllowedRoles(Set<String> allowedRoles) {
        this.allowedRoles = allowedRoles == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedRoles);
    }

    @PostConstruct
    void validate() {
        if (!enabled) {
            throw new IllegalStateException(
                    "systivex.security.jwt.enabled must remain true and cannot be disabled");
        }
        if (isBlank(issuerUri)) {
            throw new IllegalStateException("systivex.security.jwt.issuer-uri is required");
        }
        if (!isUrl(issuerUri)) {
            throw new IllegalStateException("systivex.security.jwt.issuer-uri must be an HTTP(S) URL");
        }
        if (!isBlank(jwkSetUri) && !isUrl(jwkSetUri)) {
            throw new IllegalStateException("systivex.security.jwt.jwk-set-uri must be an HTTP(S) URL");
        }
        if (isBlank(acceptedAudience)) {
            throw new IllegalStateException("systivex.security.jwt.accepted-audience is required");
        }
        if (isBlank(roleClaim)) {
            throw new IllegalStateException("systivex.security.jwt.role-claim is required");
        }
        if (clockSkewSeconds < 0 || clockSkewSeconds > 300) {
            throw new IllegalStateException(
                    "systivex.security.jwt.clock-skew-seconds must be between 0 and 300");
        }
        if (allowedRoles.isEmpty()) {
            throw new IllegalStateException("systivex.security.jwt.allowed-roles must not be empty");
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }
}