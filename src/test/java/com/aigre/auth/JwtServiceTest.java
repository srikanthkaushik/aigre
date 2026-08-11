package com.aigre.auth;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    // 256-bit key, test-only -- not the real application.yml secret.
    private static final String TEST_SECRET =
            Base64.getEncoder().encodeToString("test-only-secret-key-32-bytes-long!!".getBytes());

    @Test
    void issuedTokenParsesBackToTheSamePrincipal() {
        JwtService service = new JwtService(TEST_SECRET, 8);
        EmployeePrincipal original =
                new EmployeePrincipal(UUID.randomUUID(), "priya.nakamura", "Priya Nakamura", "DOT", "AGENT");

        String token = service.issueToken(original);
        Optional<EmployeePrincipal> parsed = service.parseToken(token);

        assertThat(parsed).contains(original);
    }

    @Test
    void malformedTokenParsesToEmpty() {
        JwtService service = new JwtService(TEST_SECRET, 8);

        assertThat(service.parseToken("not-a-real-token")).isEmpty();
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        JwtService issuer = new JwtService(TEST_SECRET, 8);
        String otherSecret = Base64.getEncoder().encodeToString("a-completely-different-32-byte-key!".getBytes());
        JwtService verifier = new JwtService(otherSecret, 8);

        String token = issuer.issueToken(new EmployeePrincipal(UUID.randomUUID(), "u", "n", "DOT", "AGENT"));

        assertThat(verifier.parseToken(token)).isEmpty();
    }

    @Test
    void alreadyExpiredTokenIsRejected() {
        // 0-hour expiration -- the token expires essentially immediately.
        JwtService service = new JwtService(TEST_SECRET, 0);
        String token = service.issueToken(new EmployeePrincipal(UUID.randomUUID(), "u", "n", "DOT", "AGENT"));

        assertThat(service.parseToken(token)).isEmpty();
    }
}
