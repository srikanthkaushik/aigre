package com.aigre.query;

import java.time.LocalDate;

public record DailySentimentLevels(
        LocalDate date,
        int noConfidence,
        int lowConfidence,
        int neutral,
        int moderateConfidence,
        int highConfidence) {
}
