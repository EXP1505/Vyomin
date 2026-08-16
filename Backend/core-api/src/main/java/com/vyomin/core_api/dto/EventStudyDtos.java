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
     *
     * bootstrapPValue/nullDistributionMean/nullDistributionStd test meanReturn against the null
     * "this event type doesn't predict basket movement": a bootstrap distribution of the mean
     * basket return on independentWindowCount random trading days in the same range. A small
     * p-value means the real meanReturn sits in the tail of what random days alone would produce.
     * All three are null when independentWindowCount is 0 (nothing to test).
     */
    public record WindowSummary(int windowDays, int independentWindowCount, BigDecimal meanReturn, BigDecimal hitRate,
                                 int distinctEventCountThisWindow, long totalArticleCount, BigDecimal coverage,
                                 BigDecimal bootstrapPValue, BigDecimal nullDistributionMean, BigDecimal nullDistributionStd) {
    }

    public record EventResult(LocalDate eventDate, String actor1, String actor2, long articleCount,
                               Map<Integer, BigDecimal> returns) {
    }

    public record EventStudyResponse(long distinctEventCount, List<WindowSummary> summary, List<EventResult> events) {
    }
}