package com.aigre.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-side department-scoping check for the 4 employee endpoints that act on a specific
 * grievance by ID (GET/resume the workflow status, mark resolved/closed) -- SecurityConfig's
 * role rules (SUPERVISOR-only on the two mutations) aren't enough on their own, since any
 * authenticated employee could otherwise still reach another department's grievance by ID.
 * `GrievanceQueryController.list()`/the pending/department-queue tables don't need this: they
 * already derive their department filter from the principal directly rather than trusting a
 * client-supplied value, so there's nothing to compare against.
 */
public final class DepartmentAccess {

    private DepartmentAccess() {
    }

    public static void requireOwnDepartment(EmployeePrincipal principal, String grievanceDepartment) {
        if (grievanceDepartment == null || !grievanceDepartment.equals(principal.departmentId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "This grievance belongs to a different department.");
        }
    }
}
