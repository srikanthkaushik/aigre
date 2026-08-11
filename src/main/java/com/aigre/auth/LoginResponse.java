package com.aigre.auth;

import java.util.UUID;

public record LoginResponse(String token, UUID employeeId, String name, String departmentId, String role) {
}
