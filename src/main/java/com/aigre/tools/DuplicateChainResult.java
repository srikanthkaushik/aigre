package com.aigre.tools;

import java.util.List;

/** hopsToOriginal counts links walked; 0 means the queried grievance is itself the original. */
public record DuplicateChainResult(String queriedId, String trueOriginalId, int hopsToOriginal, List<String> chain) {
}
