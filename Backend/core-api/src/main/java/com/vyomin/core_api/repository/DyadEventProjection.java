package com.vyomin.core_api.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row per distinct (event_type, actor1_country_code, actor2_country_code, event_date) group
 * from gdelt_event_history - the collapsed, dyad-level view of what may be many raw GDELT rows
 * reporting the same real-world event.
 */
public interface DyadEventProjection {
    String getEventType();
    String getActor1CountryCode();
    String getActor2CountryCode();
    LocalDate getEventDate();
    BigDecimal getAvgSeverity();
    BigDecimal getAvgTone();
    long getArticleCount();
    String getSourceUrl();
}