package com.aigre.admin;

/**
 * A department-onboarding request that's well-formed JSON but semantically invalid (e.g. a
 * malformed department code) -- distinct from IllegalArgumentException, which means "bad
 * grievance ID" everywhere it's thrown elsewhere in this codebase (see ApiExceptionHandler's
 * javadoc), and from IllegalStateException, which means "no paused workflow to act on."
 * Reusing either here would break those established, documented meanings.
 */
public class InvalidDepartmentRequestException extends RuntimeException {

    public InvalidDepartmentRequestException(String message) {
        super(message);
    }
}
