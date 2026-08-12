package com.vyomin.core_api.service.stooq;

import java.time.LocalDate;

/**
 * Isolates the Stooq HTTP call so the price source can be swapped later without touching
 * PriceBackfillService.
 */
public interface StooqPriceClient {
    String fetchDailyCsv(String ticker, LocalDate start, LocalDate end);
}