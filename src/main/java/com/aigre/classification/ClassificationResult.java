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

    /**
     * A partial classification (e.g. department guessed but category/priority came back null --
     * confirmed live: qwen2.5:7b did exactly this on "my vehicle's title has the address printed
     * incorrectly", returning department=DMV with category/priority null) must NOT count as
     * confident -- committing it would silently write incomplete data instead of routing to human
     * review the way a genuinely low-confidence or ambiguous case does.
     */
    public boolean isConfident() {
        return actionable
                && department != null
                && category != null
                && priority != null
                && confidence >= CONFIDENCE_THRESHOLD;
    }

    /** -1.0 confidence is the "genuinely unparseable" sentinel — distinct from a legitimate low score. */
    public static ClassificationResult unparseable(String rawResponse) {
        return new ClassificationResult(null, null, null, -1.0, "NEUTRAL", 0.0, false, "UNPARSEABLE: " + rawResponse);
    }
}
