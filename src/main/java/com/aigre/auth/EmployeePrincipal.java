package com.aigre.auth;

import java.util.UUID;

/**
 * The authenticated identity for an employee request -- set as the Authentication's principal by
 * JwtAuthenticationWebFilter after validating the bearer token, and resolvable directly in
 * controller methods via @AuthenticationPrincipal.
 */
public record EmployeePrincipal(UUID id, String username, String name, String departmentId, String role) {

    public boolean isSupervisor() {
        return "SUPERVISOR".equals(role);
    }
}
