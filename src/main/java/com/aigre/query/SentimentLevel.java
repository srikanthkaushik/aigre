package com.aigre.query;

/**
 * 5-band confidence scale over the continuous -1..1 sentiment_score produced by
 * LlmGrievanceClassifier. Single source of truth for the band boundaries so they're never
 * duplicated between Java and SQL. Bands are [lowerBound, upperBound) except HIGH_CONFIDENCE,
 * whose upperBound (1.0) is inclusive.
 */
public enum SentimentLevel {
    NO_CONFIDENCE("No Confidence", -1.0, -0.6),
    LOW_CONFIDENCE("Low Confidence", -0.6, -0.2),
    NEUTRAL("Neutral", -0.2, 0.2),
    MODERATE_CONFIDENCE("Moderate Confidence", 0.2, 0.6),
    HIGH_CONFIDENCE("High Confidence", 0.6, 1.0);

    public final String label;
    public final double lowerBound;
    public final double upperBound;

    SentimentLevel(String label, double lowerBound, double upperBound) {
        this.label = label;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }
}
