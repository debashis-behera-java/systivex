package com.soubhagya.systivex.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fail-closed configuration: the trusted issuer is mandatory, disabling
 * security is rejected, and invalid values fail fast at startup. There is
 * no security-off fallback that leaves the API accessible.
 */
class SystivexSecurityPropertiesTest {

    private static SystivexSecurityProperties valid() {
        SystivexSecurityProperties properties = new SystivexSecurityProperties();
        properties.setIssuerUri("https://issuer.example");
        return properties;
    }

    @Test
    void validDefaultsPass() {
        valid().validate();
    }

    @Test
    void disablingSecurityIsRejected() {
        SystivexSecurityProperties properties = valid();
        properties.setEnabled(false);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingIssuerIsRejected() {
        for (String issuer : new String[] {null, "", "   "}) {
            SystivexSecurityProperties properties = new SystivexSecurityProperties();
            properties.setIssuerUri(issuer);
            assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void nonUrlIssuerIsRejected() {
        SystivexSecurityProperties properties = new SystivexSecurityProperties();
        properties.setIssuerUri("not-a-url");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidJwkSetUriIsRejectedButBlankIsAbsent() {
        SystivexSecurityProperties bad = valid();
        bad.setJwkSetUri("not-a-url");
        assertThatThrownBy(bad::validate).isInstanceOf(IllegalStateException.class);

        SystivexSecurityProperties blank = valid();
        blank.setJwkSetUri("   ");
        blank.validate();
        assertThat(blank.getJwkSetUri()).isEmpty();
    }

    @Test
    void blankAudienceAndClaimAreRejected() {
        SystivexSecurityProperties audience = valid();
        audience.setAcceptedAudience("  ");
        assertThatThrownBy(audience::validate).isInstanceOf(IllegalStateException.class);

        SystivexSecurityProperties claim = valid();
        claim.setRoleClaim(null);
        assertThatThrownBy(claim::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clockSkewBoundsAreEnforced() {
        for (long skew : new long[] {-1, 301}) {
            SystivexSecurityProperties properties = valid();
            properties.setClockSkewSeconds(skew);
            assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        }
        SystivexSecurityProperties ok = valid();
        ok.setClockSkewSeconds(0);
        ok.validate();
    }

    @Test
    void emptyAllowlistIsRejected() {
        SystivexSecurityProperties properties = valid();
        properties.setAllowedRoles(Set.of());
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
