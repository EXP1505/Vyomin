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

    /**
     * independentWindowCount is the trustworthy sample size: the count of DISTINCT trading-day
     * baselines (t0) with a non-null basket return for this window - dyad-events sharing a t0
     * (e.g. a Saturday and the Monday after it both resolving to the same prior trading day)
     * produce identical returns and must not be counted as separate observations.
     *
     * distinctEventCountThisWindow and totalArticleCount are descriptive only - "how much
     * happened / how much coverage" - never a substitute for independentWindowCount as a sample
     * size.
     */
    public record WindowSummary(int windowDays, int independentWindowCount, BigDecimal meanReturn, BigDecimal hitRate,
                                 int distinctEventCountThisWindow, long totalArticleCount, BigDecimal coverage) {
    }

    public record EventResult(LocalDate eventDate, String actor1, String actor2, long articleCount,
                               Map<Integer, BigDecimal> returns) {
    }

    public record EventStudyResponse(long distinctEventCount, List<WindowSummary> summary, List<EventResult> events) {
    }
}