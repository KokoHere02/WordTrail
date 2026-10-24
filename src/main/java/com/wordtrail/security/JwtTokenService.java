package com.wordtrail.security;

import com.wordtrail.common.exception.BusinessException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.time.*;
import java.util.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenService {
    public record Identity(long userId,long authVersion,String sessionId) {}
    private final SecretKey key;
    private final JwtParser parser;
    private final String issuer;
    private final Duration accessTtl;
    private final Clock clock;

    public JwtTokenService(@Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.issuer:wordtrail}") String issuer,
            @Value("${app.jwt.access-ttl:30m}") Duration accessTtl, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET_BASE64 is required; configure a cryptographically random Base64 key");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT_SECRET_BASE64 must be valid Base64");
        }
        if (decoded.length < 32) {
            throw new IllegalStateException("JWT_SECRET_BASE64 must decode to at least 32 random bytes for HS256");
        }
        if (issuer == null || issuer.isBlank() || accessTtl == null || accessTtl.toSeconds() < 1) {
            throw new IllegalStateException("JWT issuer must be nonblank and access TTL must be at least one second");
        }
        this.key = new SecretKeySpec(decoded, "HmacSHA256");
        this.issuer = issuer;
        this.accessTtl = accessTtl;
        this.clock = clock;
        var builder = Jwts.parser().verifyWith(key).requireIssuer(issuer)
            .clock(() -> Date.from(clock.instant()));
        var algorithms = builder.sig();
        // JJWT 0.12.6 cannot temporarily hold an empty algorithm registry.
        // Remove every other algorithm while retaining HS256 throughout.
        for (var algorithm : Jwts.SIG.get().values()) {
            if (!algorithm.getId().equals(Jwts.SIG.HS256.getId())) { algorithms.remove(algorithm); }
        }
        this.parser = algorithms.and().build();
    }

    public String issue(long userId) {
        return issue(userId,0,null);
    }
    public String issue(long userId,long authVersion,String sessionId) {
        if (userId <= 0) { throw new IllegalArgumentException("Invalid user ID"); }
        if(authVersion<0 || sessionId!=null && !sessionId.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid token identity");
        Instant now = clock.instant();
        var builder=Jwts.builder().subject(Long.toString(userId)).issuer(issuer)
            .issuedAt(Date.from(now)).expiration(Date.from(now.plus(accessTtl)))
            .id(UUID.randomUUID().toString()).claim("av",authVersion);
        if(sessionId!=null) builder.claim("sid",sessionId);
        return builder.signWith(key,Jwts.SIG.HS256).compact();
    }

    public long verify(String token) {
        return identity(token).userId();
    }
    public Identity identity(String token) {
        try {
            var claims = parser.parseSignedClaims(token).getPayload();
            if (claims.getSubject() == null || !claims.getSubject().matches("[1-9][0-9]{0,18}")
                    || claims.getIssuedAt() == null || claims.getExpiration() == null
                    || claims.getId() == null || claims.getId().isBlank()
                    || claims.getIssuedAt().toInstant().isAfter(clock.instant())
                    || !claims.getExpiration().after(claims.getIssuedAt())) {
                throw unauthorized();
            }
            long userId = Long.parseLong(claims.getSubject());
            if (userId <= 0) { throw unauthorized(); }
            Object version=claims.get("av");long authVersion=0;
            if(version!=null) {
                if(!(version instanceof Number) || !version.toString().matches("[0-9]{1,19}")) throw unauthorized();
                authVersion=Long.parseLong(version.toString());
                if(authVersion<0) throw unauthorized();
            }
            Object rawSession=claims.get("sid");String sessionId=null;
            if(rawSession!=null) {
                if(!(rawSession instanceof String sid) || !sid.matches("[a-f0-9]{32}")) throw unauthorized();
                sessionId=(String)rawSession;
            }
            return new Identity(userId,authVersion,sessionId);
        } catch (JwtException | IllegalArgumentException ex) {
            // Never expose parser messages or the rejected token.
            throw unauthorized();
        }
    }

    public long expiresIn() { return accessTtl.toSeconds(); }

    public static BusinessException unauthorized() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请提供有效的 Access Token");
    }
}
