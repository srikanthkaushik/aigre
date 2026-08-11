package com.aigre.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and validates the JWTs employees authenticate with. Signing key is a configured
 * base64-encoded secret (application.yml), not regenerated per restart -- a per-restart random
 * key would log every employee out on every backend restart, which happens often in this dev
 * workflow. Fine for a single-instance demo; a real deployment would pull this from a secrets
 * manager, not a properties file.
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long expirationMillis;

    public JwtService(
            @Value("${security.jwt.secret}") String base64Secret,
            @Value("${security.jwt.expiration-hours:8}") long expirationHours) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
        this.expirationMillis = expirationHours * 3600_000L;
    }

    public String issueToken(EmployeePrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(principal.id().toString())
                .claim("username", principal.username())
                .claim("name", principal.name())
                .claim("department", principal.departmentId())
                .claim("role", principal.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMillis)))
                .signWith(key)
                .compact();
    }

    public Optional<EmployeePrincipal> parseToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Optional.of(new EmployeePrincipal(
                    UUID.fromString(claims.getSubject()),
                    claims.get("username", String.class),
                    claims.get("name", String.class),
                    claims.get("department", String.class),
                    claims.get("role", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            // Expired, malformed, bad signature, or a subject that isn't a UUID -- all treated
            // the same way by the caller (JwtAuthenticationWebFilter): no authentication set,
            // request proceeds unauthenticated and Spring Security's own authorization rules
            // decide whether that's allowed.
            return Optional.empty();
        }
    }
}
