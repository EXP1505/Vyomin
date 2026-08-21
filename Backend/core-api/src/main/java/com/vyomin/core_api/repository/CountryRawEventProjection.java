package com.vyomin.core_api.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One raw (uncollapsed) gdelt_event_history row involving a single country, for display purposes. */
public interface CountryRawEventProjection {
    LocalDate getEventDate();
    String getEventType();
    String getActor1();
    String getActor2();
    BigDecimal getSeverityScore();
    String getSourceUrl();
}
