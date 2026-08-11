package com.aigre.retrieval;

import java.util.Map;

public record RetrievedSource(String text, Map<String, Object> metadata, double vectorScore, double rerankScore) {
}
