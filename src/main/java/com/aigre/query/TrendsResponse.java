package com.aigre.query;

import java.util.List;

public record TrendsResponse(
        List<DailyCount> volumeByDay,
        List<CategoryCount> byCategory,
        List<PriorityCount> byPriority,
        List<DailySentiment> sentimentByDay,
        SlaSnapshot slaSnapshot) {
}
