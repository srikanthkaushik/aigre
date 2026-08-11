package com.aigre.auth;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(NamedParameterJdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(String username, String password) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, department_id, name, role, password_hash FROM department_employees WHERE username = :username",
                new MapSqlParameterSource("username", username));

        // Same failure message and shape whether the username doesn't exist or the password is
        // wrong -- distinguishing the two in the response would let a caller enumerate valid
        // usernames.
        if (rows.isEmpty()) {
            throw new BadCredentialsException("Invalid username or password.");
        }
        Map<String, Object> row = rows.get(0);
        String storedHash = (String) row.get("password_hash");
        if (storedHash == null || !passwordEncoder.matches(password, storedHash)) {
            throw new BadCredentialsException("Invalid username or password.");
        }

        EmployeePrincipal principal = new EmployeePrincipal(
                (UUID) row.get("id"),
                username,
                (String) row.get("name"),
                (String) row.get("department_id"),
                (String) row.get("role"));

        String token = jwtService.issueToken(principal);
        return new LoginResponse(token, principal.id(), principal.name(), principal.departmentId(), principal.role());
    }
}
