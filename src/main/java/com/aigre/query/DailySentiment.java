package com.aigre.query;

import java.time.LocalDate;

public record DailySentiment(LocalDate date, double avgSentiment) {
}
