package com.vyomin.core_api.service;

import com.vyomin.core_api.dto.EventStudyDtos.EventResult;
import com.vyomin.core_api.dto.EventStudyDtos.EventStudyRequest;
import com.vyomin.core_api.dto.EventStudyDtos.EventStudyResponse;
import com.vyomin.core_api.dto.EventStudyDtos.WindowSummary;
import com.vyomin.core_api.model.PriceDaily;
import com.vyomin.core_api.repository.DyadEventProjection;
import com.vyomin.core_api.repository.GdeltEventHistoryRepository;
import com.vyomin.core_api.repository.PriceDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses raw gdelt_event_history rows into distinct dyad-level events (STEP 1), runs each
 * through ForwardReturnService's basket average (STEP 2), and aggregates per requested window
 * (STEP 3) - the diagnostic event-study endpoint's business logic. ForwardReturnService itself is
 * untouched; this only consumes it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventStudyService {

    private static final String TRADING_CALENDAR_TICKER = "SPY";
    private static final MathContext AGG_MATH_CONTEXT = new MathContext(10, RoundingMode.HALF_UP);

    private final GdeltEventHistoryRepository gdeltEventHistoryRepository;
    private final PriceDailyRepository priceDailyRepository;
    private final ForwardReturnService forwardReturnService;

    // Only for countTradingDaysInRange()'s coverage denominator - t0 resolution itself now goes
    // through ForwardReturnService.resolveBaselineTradingDay() instead of a local copy.
    private volatile List<LocalDate> tradingDaysCache;

    public EventStudyResponse runEventStudy(EventStudyRequest request) {
        String eventType = blankToNull(request.eventType());
        String actor1CountryCode = blankToNull(request.actor1CountryCode());
        String actor2CountryCode = blankToNull(request.actor2CountryCode());

        List<DyadEventProjection> dyadEvents = gdeltEventHistoryRepository.findDyadEvents(
                eventType, actor1CountryCode, actor2CountryCode, request.dateFrom(), request.dateTo());

        long rawRowCount = gdeltEventHistoryRepository.countRawRows(
                eventType, actor1CountryCode, actor2CountryCode, request.dateFrom(), request.dateTo());
        long includedRawRows = dyadEvents.stream().mapToLong(DyadEventProjection::getArticleCount).sum();
        log.info("Event study dyad collapse: {} raw rows -> {} distinct dyad-events " +
                        "({} raw rows included, {} excluded for missing actor identification)",
                rawRowCount, dyadEvents.size(), includedRawRows, rawRowCount - includedRawRows);

        List<Integer> windows = request.windows();

        // Per window k: distinct t0 -> its return (one entry per trading-day baseline, however
        // many dyad-events shared it) - this is what independentWindowCount/meanReturn/hitRate
        // are computed from, NOT the raw per-dyad-event return list.
        Map<Integer, Map<LocalDate, BigDecimal>> returnByT0PerWindow = new LinkedHashMap<>();
        // Per window k: how many dyad-events had a non-null return - descriptive only.
        Map<Integer, Integer> dyadEventCountByWindow = new LinkedHashMap<>();
        Map<Integer, Long> articleCountByWindow = new LinkedHashMap<>();
        for (Integer k : windows) {
            returnByT0PerWindow.put(k, new LinkedHashMap<>());
            dyadEventCountByWindow.put(k, 0);
            articleCountByWindow.put(k, 0L);
        }

        List<EventResult> events = new ArrayList<>(dyadEvents.size());
        for (DyadEventProjection event : dyadEvents) {
            LocalDate t0 = forwardReturnService.resolveBaselineTradingDay(event.getEventDate());
            Map<Integer, BigDecimal> perWindowReturns = new LinkedHashMap<>();
            for (Integer k : windows) {
                BasketForwardReturn result = forwardReturnService.basketForwardReturn(request.basket(), event.getEventDate(), k);
                BigDecimal r = result.averageReturn();
                perWindowReturns.put(k, r);
                if (r != null) {
                    dyadEventCountByWindow.merge(k, 1, Integer::sum);
                    articleCountByWindow.merge(k, event.getArticleCount(), Long::sum);
                    if (t0 != null) {
                        // Same t0 always yields the same return for this basket/window (it's what
                        // basketForwardReturn resolves internally from eventDate) - putIfAbsent so
                        // a t0 shared by many dyad-events counts once, not once per dyad-event.
                        returnByT0PerWindow.get(k).putIfAbsent(t0, r);
                    }
                }
            }
            events.add(new EventResult(event.getEventDate(), event.getActor1CountryCode(), event.getActor2CountryCode(),
                    event.getArticleCount(), perWindowReturns));
        }

        long totalDistinctTradingDaysInRange = countTradingDaysInRange(request.dateFrom(), request.dateTo());

        List<WindowSummary> summary = new ArrayList<>();
        for (Integer k : windows) {
            summary.add(summarizeWindow(k, returnByT0PerWindow.get(k), dyadEventCountByWindow.get(k),
                    articleCountByWindow.get(k), totalDistinctTradingDaysInRange));
        }

        return new EventStudyResponse(dyadEvents.size(), summary, events);
    }

    /**
     * independentWindowCount is the count of DISTINCT t0 values with a non-null return for this
     * window - the real, non-pseudo-replicated sample size. meanReturn/hitRate are null (not 0)
     * when independentWindowCount is 0, matching ForwardReturnService's "no data" convention.
     */
    private WindowSummary summarizeWindow(int windowDays, Map<LocalDate, BigDecimal> returnByT0,
                                           int distinctEventCountThisWindow, long totalArticleCount,
                                           long totalDistinctTradingDaysInRange) {
        int independentWindowCount = returnByT0.size();
        BigDecimal meanReturn = null;
        BigDecimal hitRate = null;
        if (independentWindowCount > 0) {
            BigDecimal sum = returnByT0.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            meanReturn = sum.divide(BigDecimal.valueOf(independentWindowCount), AGG_MATH_CONTEXT);
            long positiveCount = returnByT0.values().stream().filter(r -> r.compareTo(BigDecimal.ZERO) > 0).count();
            hitRate = BigDecimal.valueOf(positiveCount).divide(BigDecimal.valueOf(independentWindowCount), AGG_MATH_CONTEXT);
        }
        BigDecimal coverage = totalDistinctTradingDaysInRange == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(independentWindowCount).divide(BigDecimal.valueOf(totalDistinctTradingDaysInRange), AGG_MATH_CONTEXT);
        return new WindowSummary(windowDays, independentWindowCount, meanReturn, hitRate,
                distinctEventCountThisWindow, totalArticleCount, coverage);
    }

    private long countTradingDaysInRange(LocalDate dateFrom, LocalDate dateTo) {
        return tradingDays().stream().filter(d -> !d.isBefore(dateFrom) && !d.isAfter(dateTo)).count();
    }

    private List<LocalDate> tradingDays() {
        List<LocalDate> cached = tradingDaysCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (tradingDaysCache == null) {
                tradingDaysCache = priceDailyRepository.findByTickerOrderByTradeDateAsc(TRADING_CALENDAR_TICKER).stream()
                        .map(PriceDaily::getTradeDate)
                        .toList();
            }
            return tradingDaysCache;
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}