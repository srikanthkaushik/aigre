package com.aigre.department;

/**
 * Deliberately excludes short_name/jurisdiction_notes -- those are internal prompt-engineering
 * text (see DepartmentDirectory), no reason to expose them on this public endpoint.
 */
public record DepartmentSummary(String id, String name) {
}
