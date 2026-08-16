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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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
    private static final int BOOTSTRAP_ITERATIONS = 10_000;
    // At or above this coverage, the real event days already occupy nearly the whole trading
    // calendar in range - there's no meaningful "random" subset of days left to bootstrap against.
    private static final BigDecimal SATURATED_COVERAGE_THRESHOLD = new BigDecimal("0.98");

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
                    // t0 must fall within [dateFrom, dateTo] itself, not just the eventDate that
                    // resolved to it - an event dated at/near dateFrom (e.g. a Sunday) can resolve
                    // t0 to the last trading day BEFORE dateFrom (e.g. the prior Friday), which
                    // countTradingDaysInRange's denominator correctly excludes. Without this check
                    // that t0 still landed in the numerator, letting coverage exceed 1.0.
                    if (t0 != null && !t0.isBefore(request.dateFrom()) && !t0.isAfter(request.dateTo())) {
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

        // Precomputed ONCE per request, outside the 10,000-iteration bootstrap loop below: every
        // trading day in [dateFrom, dateTo] that has a computable basket return for window k. This
        // is the null-hypothesis population ("a random trading day in this range") the real
        // meanReturn gets tested against - restricted to non-null entries since a random draw that
        // landed on a day with no computable return couldn't contribute to a "mean return" anyway.
        Map<Integer, Map<LocalDate, BigDecimal>> eligiblePoolByWindow = precomputeEligiblePool(
                request.basket(), request.dateFrom(), request.dateTo(), windows);

        List<WindowSummary> summary = new ArrayList<>();
        for (Integer k : windows) {
            summary.add(summarizeWindow(k, returnByT0PerWindow.get(k), dyadEventCountByWindow.get(k),
                    articleCountByWindow.get(k), totalDistinctTradingDaysInRange, eligiblePoolByWindow.get(k)));
        }

        return new EventStudyResponse(dyadEvents.size(), summary, events);
    }

    /** For each window k, every trading day in range with a non-null basket return - the bootstrap's null population. */
    private Map<Integer, Map<LocalDate, BigDecimal>> precomputeEligiblePool(List<String> basket, LocalDate dateFrom,
                                                                             LocalDate dateTo, List<Integer> windows) {
        List<LocalDate> daysInRange = tradingDays().stream()
                .filter(d -> !d.isBefore(dateFrom) && !d.isAfter(dateTo))
                .toList();

        Map<Integer, Map<LocalDate, BigDecimal>> byWindow = new LinkedHashMap<>();
        for (Integer k : windows) {
            Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
            for (LocalDate day : daysInRange) {
                BigDecimal r = forwardReturnService.basketForwardReturn(basket, day, k).averageReturn();
                if (r != null) {
                    byDay.put(day, r);
                }
            }
            byWindow.put(k, byDay);
        }
        return byWindow;
    }

    /**
     * independentWindowCount is the count of DISTINCT t0 values with a non-null return for this
     * window - the real, non-pseudo-replicated sample size. meanReturn/hitRate are null (not 0)
     * when independentWindowCount is 0, matching ForwardReturnService's "no data" convention.
     */
    private WindowSummary summarizeWindow(int windowDays, Map<LocalDate, BigDecimal> returnByT0,
                                           int distinctEventCountThisWindow, long totalArticleCount,
                                           long totalDistinctTradingDaysInRange, Map<LocalDate, BigDecimal> eligiblePool) {
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

        BigDecimal[] bootstrap;
        String bootstrapStatus;
        String bootstrapStatusReason;
        if (independentWindowCount == 0) {
            bootstrap = new BigDecimal[]{null, null, null};
            bootstrapStatus = "NO_DATA";
            bootstrapStatusReason = "No qualifying events with computable returns for this window.";
        } else if (coverage.compareTo(SATURATED_COVERAGE_THRESHOLD) >= 0) {
            bootstrap = new BigDecimal[]{null, null, null};
            bootstrapStatus = "INVALID_SATURATED";
            bootstrapStatusReason = "Event coverage too high (>=98%) for a valid random comparison.";
        } else {
            bootstrap = runBootstrap(meanReturn, independentWindowCount, eligiblePool);
            bootstrapStatus = "OK";
            bootstrapStatusReason = null;
        }

        return new WindowSummary(windowDays, independentWindowCount, meanReturn, hitRate,
                distinctEventCountThisWindow, totalArticleCount, coverage,
                bootstrap[0], bootstrap[1], bootstrap[2], bootstrapStatus, bootstrapStatusReason);
    }

    /**
     * Bootstraps the null "this event type doesn't predict basket movement": draws
     * BOOTSTRAP_ITERATIONS random samples of size independentWindowCount (without replacement)
     * from eligiblePool's returns, averages each sample, and compares realMeanReturn's percentile
     * rank within that null distribution. All eligiblePool lookups happen against the map
     * precomputed once in precomputeEligiblePool() - no DB calls inside this loop.
     *
     * Returns [pValue, nullDistributionMean, nullDistributionStd].
     */
    private BigDecimal[] runBootstrap(BigDecimal realMeanReturn, int sampleSize, Map<LocalDate, BigDecimal> eligiblePool) {
        double[] pool = eligiblePool.values().stream().mapToDouble(BigDecimal::doubleValue).toArray();
        // Defensive only: the real independentWindowCount is itself a subset of eligiblePool
        // (every real event day counted there also has a non-null return), so this should never
        // trigger in practice, but caps the sample instead of throwing if pool is somehow smaller.
        int n = Math.min(sampleSize, pool.length);
        if (n == 0) {
            return new BigDecimal[]{null, null, null};
        }

        double[] nullMeans = new double[BOOTSTRAP_ITERATIONS];
        int[] indices = java.util.stream.IntStream.range(0, pool.length).toArray();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int iter = 0; iter < BOOTSTRAP_ITERATIONS; iter++) {
            // Partial Fisher-Yates: randomly select n distinct indices without allocating a fresh
            // array or list per iteration - valid uniform sampling regardless of the array's
            // starting order, so reusing/mutating `indices` across iterations is safe.
            double sum = 0;
            for (int i = 0; i < n; i++) {
                int j = i + random.nextInt(indices.length - i);
                int tmp = indices[i];
                indices[i] = indices[j];
                indices[j] = tmp;
                sum += pool[indices[i]];
            }
            nullMeans[iter] = sum / n;
        }

        double realMean = realMeanReturn.doubleValue();
        long countLessOrEqual = Arrays.stream(nullMeans).filter(v -> v <= realMean).count();
        double percentile = (double) countLessOrEqual / BOOTSTRAP_ITERATIONS;
        double pValue = Math.min(2 * Math.min(percentile, 1 - percentile), 1.0);

        double nullMean = Arrays.stream(nullMeans).average().orElse(0);
        double variance = Arrays.stream(nullMeans).map(v -> (v - nullMean) * (v - nullMean)).sum() / (BOOTSTRAP_ITERATIONS - 1);
        double nullStd = Math.sqrt(variance);

        return new BigDecimal[]{BigDecimal.valueOf(pValue), BigDecimal.valueOf(nullMean), BigDecimal.valueOf(nullStd)};
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