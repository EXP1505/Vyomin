package com.vyomin.core_api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class EventStudyDtos {

    public record EventStudyRequest(
            String eventType,
            String actor1CountryCode,
            String actor2CountryCode,
            LocalDate dateFrom,
            LocalDate dateTo,
            List<String> basket,
            List<Integer> windows
    ) {
    }

    public record WindowSummary(int windowDays, int n, BigDecimal meanReturn, BigDecimal hitRate, BigDecimal coverage) {
    }

    public record EventResult(LocalDate eventDate, String actor1, String actor2, long articleCount,
                               Map<Integer, BigDecimal> returns) {
    }

    public record EventStudyResponse(long distinctEventCount, List<WindowSummary> summary, List<EventResult> events) {
    }
}