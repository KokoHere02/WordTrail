package com.wordtrail.security;

import com.wordtrail.common.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtTokenServiceTests {
    private final byte[] key = randomKey();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
    private byte[] randomKey() {
        byte[] bytes = new byte[64];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }
    private JwtTokenService service() {
        return new JwtTokenService(Base64.getEncoder().encodeToString(key), "wordtrail", Duration.ofMinutes(30), clock);
    }
    private io.jsonwebtoken.JwtBuilder validBuilder() {
        return Jwts.builder().subject("42").issuer("wordtrail").id(UUID.randomUUID().toString())
            .issuedAt(Date.from(clock.instant())).expiration(Date.from(clock.instant().plusSeconds(1800)));
    }

    @Test void roundTripContainsRequiredClaimsAndUniqueJti() throws Exception {
        var service = service();
        String first = service.issue(42);
        assertEquals(42, service.verify(first));
        assertNotEquals(first, service.issue(42));
        String payload = new String(Base64.getUrlDecoder().decode(first.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        var claims = new tools.jackson.databind.ObjectMapper().readTree(payload);
        assertEquals(Set.of("sub", "iss", "iat", "exp", "jti"),
            new HashSet<>(claims.properties().stream().map(Map.Entry::getKey).toList()));
        assertEquals("42", claims.get("sub").asText());
        assertEquals(1800, claims.get("exp").asLong() - claims.get("iat").asLong());
    }

    @Test void missingInvalidOrWeakSecretFailsClearly() {
        for (String secret : List.of("", "invalid-base64!", Base64.getEncoder().encodeToString(new byte[8]))) {
            var error = assertThrows(IllegalStateException.class,
                () -> new JwtTokenService(secret, "wordtrail", Duration.ofMinutes(30), clock));
            assertTrue(error.getMessage().contains("JWT_SECRET_BASE64"));
        }
    }

    @Test void restrictsAlgorithmAndChecksIssuerSignatureAndExpiry() {
        var hs256 = new SecretKeySpec(key, "HmacSHA256");
        var tokens = List.of(
            validBuilder().issuer("other-issuer").signWith(hs256, Jwts.SIG.HS256).compact(),
            validBuilder().signWith(new SecretKeySpec(randomKey(), "HmacSHA256"), Jwts.SIG.HS256).compact(),
            validBuilder().issuedAt(Date.from(clock.instant().minusSeconds(60)))
                .expiration(Date.from(clock.instant().minusSeconds(1))).signWith(hs256, Jwts.SIG.HS256).compact(),
            validBuilder().signWith(new SecretKeySpec(key, "HmacSHA512"), Jwts.SIG.HS512).compact(),
            validBuilder().compact()
        );
        for (String token : tokens) {
            assertEquals(401, assertThrows(BusinessException.class, () -> service().verify(token)).status().value());
        }
    }

    @Test void rejectsMissingClaimsAndInvalidSubjects() {
        var signingKey = new SecretKeySpec(key, "HmacSHA256");
        var tokens = List.of(
            validBuilder().subject("not-a-user").signWith(signingKey, Jwts.SIG.HS256).compact(),
            validBuilder().subject("0").signWith(signingKey, Jwts.SIG.HS256).compact(),
            validBuilder().subject("9999999999999999999").signWith(signingKey, Jwts.SIG.HS256).compact(),
            validBuilder().issuedAt(null).signWith(signingKey, Jwts.SIG.HS256).compact(),
            validBuilder().expiration(null).signWith(signingKey, Jwts.SIG.HS256).compact(),
            validBuilder().id(null).signWith(signingKey, Jwts.SIG.HS256).compact(),
            validBuilder().issuedAt(Date.from(clock.instant().plusSeconds(60))).signWith(signingKey, Jwts.SIG.HS256).compact()
        );
        for (String token : tokens) {
            assertThrows(BusinessException.class, () -> service().verify(token));
        }
    }
}
