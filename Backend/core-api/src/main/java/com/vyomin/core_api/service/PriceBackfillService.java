package com.vyomin.core_api.service;

import com.vyomin.core_api.repository.PriceDailyRepository;
import com.vyomin.core_api.service.stooq.StooqPriceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backfills daily OHLCV history from Stooq into price_daily for the configured ticker set.
 * Never throws out of backfill(): a per-ticker failure is logged and recorded in the summary so
 * one bad symbol doesn't stop the rest.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PriceBackfillService {

    private static final List<String> EXPECTED_HEADER = List.of("Date", "Open", "High", "Low", "Close", "Volume");
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_BACKOFF_MS = 2000;

    private final StooqPriceClient stooqPriceClient;
    private final PriceDailyRepository priceDailyRepository;

    @Value("#{'${vyomin.analysis.tickers}'.split(',')}")
    private List<String> tickers;

    @Value("${stooq.backfill.ticker-delay-ms:1500}")
    private long tickerDelayMs;

    public Map<String, Object> backfill(LocalDate start, LocalDate end) {
        LocalDate effectiveEnd = end != null ? end : LocalDate.now();
        LocalDate effectiveStart = start != null ? start : effectiveEnd.minusYears(2);

        List<Map<String, Object>> perTicker = new ArrayList<>();
        List<String> cleanTickers = tickers.stream().map(String::trim).filter(t -> !t.isEmpty()).toList();

        for (int i = 0; i < cleanTickers.size(); i++) {
            String ticker = cleanTickers.get(i).toUpperCase();
            perTicker.add(backfillTicker(ticker, effectiveStart, effectiveEnd));

            if (i < cleanTickers.size() - 1) {
                sleepQuietly(tickerDelayMs);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("start", effectiveStart);
        summary.put("end", effectiveEnd);
        summary.put("tickers", perTicker);
        log.info("Stooq backfill summary: {}", perTicker);
        return summary;
    }

    private Map<String, Object> backfillTicker(String ticker, LocalDate start, LocalDate end) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticker", ticker);

        String csv = null;
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && csv == null; attempt++) {
            try {
                String body = stooqPriceClient.fetchDailyCsv(ticker, start, end);
                validateCsv(ticker, body);
                csv = body;
            } catch (Exception e) {
                lastError = e;
                log.warn("Stooq fetch failed for {} (attempt {}/{}): {}", ticker, attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepQuietly(RETRY_BACKOFF_MS * attempt);
                }
            }
        }

        if (csv == null) {
            log.error("Skipping {} after {} failed attempts: {}", ticker, MAX_ATTEMPTS,
                    lastError != null ? lastError.getMessage() : "unknown error");
            result.put("status", "failed");
            result.put("error", lastError != null ? lastError.getMessage() : "unknown error");
            result.put("rowsUpserted", 0);
            return result;
        }

        return upsertCsv(ticker, csv, result);
    }

    // Stooq returns "No data" or an HTML error page (bad symbol / rate limit) instead of CSV -
    // catching that here means a malformed response is skipped loudly rather than partially
    // parsed into garbage rows.
    private void validateCsv(String ticker, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("empty response from Stooq for " + ticker);
        }
        String firstLine = body.lines().findFirst().orElse("");
        List<String> headerCols = List.of(firstLine.strip().split(","));
        if (!headerCols.equals(EXPECTED_HEADER)) {
            throw new IllegalStateException("unexpected Stooq response for " + ticker
                    + " (not a CSV header, likely bad symbol or rate limit): " + firstLine.strip());
        }
    }

    private Map<String, Object> upsertCsv(String ticker, String csv, Map<String, Object> result) {
        int rows = 0;
        int failedRows = 0;
        LocalDate minDate = null;
        LocalDate maxDate = null;

        try (CSVParser parser = CSVParser.parse(new StringReader(csv),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            for (CSVRecord record : parser) {
                try {
                    LocalDate tradeDate = LocalDate.parse(record.get("Date"));
                    BigDecimal open = parseDecimal(record.get("Open"));
                    BigDecimal high = parseDecimal(record.get("High"));
                    BigDecimal low = parseDecimal(record.get("Low"));
                    BigDecimal close = parseDecimal(record.get("Close"));
                    if (close == null) {
                        failedRows++;
                        continue;
                    }
                    Long volume = parseLong(record.get("Volume"));

                    priceDailyRepository.upsert(ticker, tradeDate, open, high, low, close, volume);
                    rows++;
                    minDate = (minDate == null || tradeDate.isBefore(minDate)) ? tradeDate : minDate;
                    maxDate = (maxDate == null || tradeDate.isAfter(maxDate)) ? tradeDate : maxDate;
                } catch (Exception e) {
                    failedRows++;
                    log.warn("Skipping malformed Stooq row for {}: {} ({})", ticker, record, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse Stooq CSV for {}: {}", ticker, e.getMessage(), e);
            result.put("status", "failed");
            result.put("error", "CSV parse error: " + e.getMessage());
            result.put("rowsUpserted", rows);
            return result;
        }

        log.info("Stooq backfill for {}: {} rows upserted ({} failed), range {} to {}",
                ticker, rows, failedRows, minDate, maxDate);
        result.put("status", rows > 0 ? "success" : "no-data");
        result.put("rowsUpserted", rows);
        result.put("rowsFailed", failedRows);
        result.put("minDate", minDate);
        result.put("maxDate", maxDate);
        return result;
    }

    private BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}