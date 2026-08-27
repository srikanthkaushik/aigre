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
 * Issues and validates a long-lived, silent recognition token for a citizen's browser --
 * deliberately separate from JwtService/EmployeePrincipal, not a lighter-weight version of it.
 * Different lifetime (a year, not 8 hours -- the point is recognizing a returning citizen months
 * later), different claim shape, and a "type": "citizen" claim so a citizen token can never be
 * mistaken for (or parsed as) an EmployeePrincipal even though both reuse the same signing key.
 *
 * The token is never typed or displayed -- it's issued automatically in a submission response
 * (GrievanceWorkflowService.buildResponse()) and replayed silently from localStorage
 * (frontend ApiService). This is deliberately NOT a "type your email to look up your grievances"
 * flow: that would let anyone who knows (or guesses) a citizen's email see their full grievance
 * history, a materially bigger leak than today's "know the grievance ID" model. Possession of
 * this token is the same "secret grants access" shape the app already uses for grievance IDs,
 * just automated instead of copy-pasted -- and it only ever personalizes that citizen's own chat
 * session (com.aigre.chat.ChatController), never anything write-capable or otherwise sensitive.
 */
@Component
public class CitizenTokenService {

    private static final String TYPE_CLAIM = "type";
    private static final String CITIZEN_TYPE = "citizen";

    private final SecretKey key;
    private final long expirationMillis;

    public CitizenTokenService(
            @Value("${security.jwt.secret}") String base64Secret,
            @Value("${security.citizen-token.expiration-days:365}") long expirationDays) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
        this.expirationMillis = expirationDays * 86_400_000L;
    }

    public String issueToken(UUID citizenId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(citizenId.toString())
                .claim(TYPE_CLAIM, CITIZEN_TYPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMillis)))
                .signWith(key)
                .compact();
    }

    /** Empty for anything invalid, expired, or not a citizen-type token -- never thrown, chat must keep working anonymously. */
    public Optional<UUID> parseToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!CITIZEN_TYPE.equals(claims.get(TYPE_CLAIM, String.class))) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
