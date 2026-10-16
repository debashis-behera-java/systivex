package com.soubhagya.systivex.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public final class TestJwt {

    public static final String ISSUER = "https://test-issuer";
    public static final String AUDIENCE = "systivex";

    private static final KeyPair SIGNING_KEY;
    private static final KeyPair WRONG_KEY;

    static {
        try {
            SIGNING_KEY = keyPair();
            WRONG_KEY = keyPair();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private TestJwt() {}

    public static String token(List<String> roles) {
        return token(ISSUER, AUDIENCE, roles, Duration.ofHours(1));
    }

    /** Fixed-subject token for separation-of-duties tests. */
    public static String tokenWithSubject(String subject, List<String> roles) {
        return tokenWithSubject(ISSUER, subject, roles);
    }

    /** Fixed issuer-and-subject token for identity-pair tests. */
    public static String tokenWithSubject(String issuer, String subject, List<String> roles) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer == null ? ISSUER : issuer)
                .audience(AUDIENCE)
                .claim("roles", roles)
                .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(30)));
        return sign(builder.build(), SIGNING_KEY);
    }

    public static String token(String issuer, String audience, List<String> roles, Duration validity) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject("test-subject-" + UUID.randomUUID())
                .issuer(issuer == null ? ISSUER : issuer)
                .audience(audience == null ? AUDIENCE : audience)
                .claim("roles", roles)
                .expirationTime(Date.from(Instant.now().plus(validity)))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(30)));
        return sign(builder.build(), SIGNING_KEY);
    }

    public static String expiredToken() {
        return sign(new JWTClaimsSet.Builder()
                .subject("expired")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .claim("roles", List.of("TWIN_READ"))
                .expirationTime(Date.from(Instant.now().minus(Duration.ofMinutes(30))))
                .notBeforeTime(Date.from(Instant.now().minus(Duration.ofHours(1))))
                .build(), SIGNING_KEY);
    }

    public static String notYetValidToken() {
        return sign(new JWTClaimsSet.Builder()
                .subject("future")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .claim("roles", List.of("TWIN_READ"))
                .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .notBeforeTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .build(), SIGNING_KEY);
    }

    public static String wrongIssuerToken() {
        return token("https://attacker-issuer", AUDIENCE, List.of("TWIN_READ"), Duration.ofHours(1));
    }

    public static String wrongAudienceToken() {
        return token(ISSUER, "wrong-audience", List.of("TWIN_READ"), Duration.ofHours(1));
    }

    public static String wrongSignatureToken() {
        return sign(new JWTClaimsSet.Builder()
                .subject("bad-signature")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .claim("roles", List.of("TWIN_READ"))
                .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(30)))
                .build(), WRONG_KEY);
    }

    public static String malformedRoleToken(Object rolesClaim) {
        return sign(new JWTClaimsSet.Builder()
                .subject("malformed-role")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .claim("roles", rolesClaim)
                .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(30)))
                .build(), SIGNING_KEY);
    }

    public static String notBeforeToken(Duration notBeforeOffset) {
        return sign(new JWTClaimsSet.Builder()
                .subject("not-before-boundary")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .claim("roles", List.of("TWIN_READ"))
                .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .notBeforeTime(Date.from(Instant.now().plus(notBeforeOffset)))
                .build(), SIGNING_KEY);
    }

    public static com.nimbusds.jose.jwk.source.JWKSource<com.nimbusds.jose.proc.SecurityContext> jwkSource() {
        return new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(
                new com.nimbusds.jose.jwk.JWKSet(rsaKey().toPublicJWK()));
    }

    public static String jwkSetJson() {
        return new com.nimbusds.jose.jwk.JWKSet(rsaKey().toPublicJWK()).toString();
    }

    private static com.nimbusds.jose.jwk.RSAKey rsaKey() {
        return new com.nimbusds.jose.jwk.RSAKey.Builder(
                (java.security.interfaces.RSAPublicKey) SIGNING_KEY.getPublic())
                .privateKey((java.security.interfaces.RSAPrivateKey) SIGNING_KEY.getPrivate())
                .keyID("test-jwt-key")
                .build();
    }

    private static String sign(JWTClaimsSet claims, KeyPair key) {
        try {
            SignedJWT signed = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-jwt-key").build(), claims);
            signed.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
            return signed.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}