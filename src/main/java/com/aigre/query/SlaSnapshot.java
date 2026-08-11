package com.aigre.query;

/**
 * Deliberately three separate counts, not one "compliance %" -- resolvedLate and
 * currentlyBreachedOpen are different problems (a closed-but-late case vs. a still-open one
 * past due) and conflating them into a single percentage would hide which one is happening.
 */
public record SlaSnapshot(int resolvedOnTime, int resolvedLate, int currentlyBreachedOpen) {
}
