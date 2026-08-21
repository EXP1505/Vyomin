package com.vyomin.core_api.dto;

import com.vyomin.core_api.dto.EventStudyDtos.WindowSummary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class EventStudyDossierDtos {

    public record EventTypeCount(String eventType, long count) {
    }

    public record RecentEvent(LocalDate eventDate, String eventType, String actor1, String actor2,
                               BigDecimal severityScore, String sourceUrl) {
    }

    public record EventSummary(long totalEvents, List<EventTypeCount> byEventType, List<RecentEvent> recentEvents) {
    }

    /** null when the country had zero matching events in range - no bootstrap is run against no data. */
    public record MarketRelevance(List<WindowSummary> summary, String note) {
    }

    public record CountryDossierResponse(String countryCode, LocalDate dateFrom, LocalDate dateTo,
                                          EventSummary eventSummary, MarketRelevance marketRelevance) {
    }
}
