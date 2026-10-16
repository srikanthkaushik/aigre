package com.aigre.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and validates the JWTs employees authenticate with. The signing key is the shared
 * jwtSigningKey bean (JwtKeyConfig): SECURITY_JWT_SECRET when set, otherwise random per boot.
 * Fine for a single-instance demo; a real deployment would pull it from a secrets manager.
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long expirationMillis;

    @Autowired
    public JwtService(
            SecretKey jwtSigningKey,
            @Value("${security.jwt.expiration-hours:8}") long expirationHours) {
        this.key = jwtSigningKey;
        this.expirationMillis = expirationHours * 3600_000L;
    }

    /** For tests that build the service directly from a base64 secret. */
    public JwtService(String base64Secret, long expirationHours) {
        this(Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret)), expirationHours);
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
