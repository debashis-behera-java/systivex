package com.soubhagya.systivex.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Exercises the real production {@code SecurityConfig.jwtDecoder()} with a
 * local test JWK Set endpoint. No {@code @Primary} test decoder is involved:
 * a custom JWK Set URI must still enforce issuer, audience, signature, and
 * timestamp validation. Clock-skew boundaries are verified deterministically
 * with wide margins (30s inside vs 120s outside a 60s skew).
 */
class ProductionJwtDecoderTest {

    private HttpServer jwkServer;
    private String jwkSetUri;

    @BeforeEach
    void startJwkServer() throws Exception {
        byte[] body = TestJwt.jwkSetJson().getBytes(StandardCharsets.UTF_8);
        jwkServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwkServer.createContext("/.well-known/jwks.json", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwkServer.start();
        jwkSetUri = "http://127.0.0.1:" + jwkServer.getAddress().getPort()
                + "/.well-known/jwks.json";
    }

    @AfterEach
    void stopJwkServer() {
        if (jwkServer != null) {
            jwkServer.stop(0);
        }
    }

    private JwtDecoder productionDecoder() {
        SystivexSecurityProperties properties = new SystivexSecurityProperties();
        properties.setIssuerUri(TestJwt.ISSUER);
        properties.setJwkSetUri(jwkSetUri);
        properties.setAcceptedAudience(TestJwt.AUDIENCE);
        properties.setClockSkewSeconds(60);
        properties.setAllowedRoles(
                Set.of("TWIN_READ", "TWIN_ANALYZE", "TWIN_OPERATOR", "TWIN_ADMIN"));
        properties.validate();
        return new SecurityConfig().jwtDecoder(properties);
    }

    @Test
    void correctlyScopedTokenDecodesThroughCustomJwkSetUri() {
        JwtDecoder decoder = productionDecoder();
        org.springframework.security.oauth2.jwt.Jwt jwt =
                decoder.decode(TestJwt.token(List.of("TWIN_READ")));
        assertThat(jwt.getIssuer().toString()).isEqualTo(TestJwt.ISSUER);
        assertThat(jwt.getAudience()).contains(TestJwt.AUDIENCE);
    }

    @Test
    void wrongIssuerIsRejectedDespiteCustomJwkSetUri() {
        JwtDecoder decoder = productionDecoder();
        assertThatThrownBy(() -> decoder.decode(TestJwt.wrongIssuerToken()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void wrongAudienceAndSignatureAreRejectedThroughCustomJwkSetUri() {
        JwtDecoder decoder = productionDecoder();
        assertThatThrownBy(() -> decoder.decode(TestJwt.wrongAudienceToken()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> decoder.decode(TestJwt.wrongSignatureToken()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void expiryWithinSkewPassesBeyondSkewFails() {
        JwtDecoder decoder = productionDecoder();
        String justExpired = TestJwt.token(
                TestJwt.ISSUER, TestJwt.AUDIENCE, List.of("TWIN_READ"), Duration.ofSeconds(-30));
        assertThat(decoder.decode(justExpired).getIssuer().toString())
                .isEqualTo(TestJwt.ISSUER);
        String longExpired = TestJwt.token(
                TestJwt.ISSUER, TestJwt.AUDIENCE, List.of("TWIN_READ"), Duration.ofSeconds(-120));
        assertThatThrownBy(() -> decoder.decode(longExpired))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void notBeforeWithinSkewPassesBeyondSkewFails() {
        JwtDecoder decoder = productionDecoder();
        assertThat(decoder.decode(TestJwt.notBeforeToken(Duration.ofSeconds(30)))
                        .getIssuer().toString())
                .isEqualTo(TestJwt.ISSUER);
        assertThatThrownBy(
                        () -> decoder.decode(TestJwt.notBeforeToken(Duration.ofSeconds(120))))
                .isInstanceOf(RuntimeException.class);
    }
}
