package com.aigre.classification;

public record ClassificationResult(
        String department,
        String category,
        String priority,
        double confidence,
        String sentimentLabel,
        double sentimentScore,
        boolean actionable,
        String reasoning) {

    private static final double CONFIDENCE_THRESHOLD = 0.5;

    public boolean isConfident() {
        return actionable && department != null && confidence >= CONFIDENCE_THRESHOLD;
    }

    /** -1.0 confidence is the "genuinely unparseable" sentinel — distinct from a legitimate low score. */
    public static ClassificationResult unparseable(String rawResponse) {
        return new ClassificationResult(null, null, null, -1.0, "NEUTRAL", 0.0, false, "UNPARSEABLE: " + rawResponse);
    }
}
