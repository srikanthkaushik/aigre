package com.aigre.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.util.Base64;

/**
 * The one HMAC signing key shared by JwtService (employee logins) and CitizenTokenService
 * (silent citizen recognition). It comes from security.jwt.secret, normally set per deployment
 * via SECURITY_JWT_SECRET. No value is committed: this repo is public, so any committed key
 * would let anyone forge an ADMIN token against every deployment that kept it.
 *
 * Unset (the default for local dev) falls back to a random key per boot, so dev needs no setup.
 * The cost is that every restart logs employees out and drops citizen recognition, hence the WARN.
 */
@Configuration
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);
    private static final int MIN_KEY_BYTES = 32; // HS256 needs at least 256 bits

    @Bean
    public SecretKey jwtSigningKey(@Value("${security.jwt.secret:}") String base64Secret) {
        if (base64Secret == null || base64Secret.isBlank()) {
            log.warn("security.jwt.secret is not set -- using a random signing key for this run. "
                    + "Employees will be logged out and citizen recognition reset on every restart. "
                    + "Set SECURITY_JWT_SECRET (e.g. the output of `openssl rand -base64 32`) to keep sessions.");
            return Jwts.SIG.HS256.key().build();
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("security.jwt.secret (SECURITY_JWT_SECRET) is not valid base64. "
                    + "Generate one with `openssl rand -base64 32`.", e);
        }
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("security.jwt.secret (SECURITY_JWT_SECRET) decodes to "
                    + keyBytes.length * 8 + " bits; HS256 needs at least 256. "
                    + "Generate one with `openssl rand -base64 32`.");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
