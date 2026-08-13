package com.vyomin.core_api.service;

import com.vyomin.core_api.dto.EventStudyDtos.EventResult;
import com.vyomin.core_api.dto.EventStudyDtos.EventStudyRequest;
import com.vyomin.core_api.dto.EventStudyDtos.EventStudyResponse;
import com.vyomin.core_api.dto.EventStudyDtos.WindowSummary;
import com.vyomin.core_api.repository.DyadEventProjection;
import com.vyomin.core_api.repository.GdeltEventHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
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

    private static final MathContext AGG_MATH_CONTEXT = new MathContext(10, RoundingMode.HALF_UP);

    private final GdeltEventHistoryRepository gdeltEventHistoryRepository;
    private final ForwardReturnService forwardReturnService;

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
        Map<Integer, List<BigDecimal>> nonNullReturnsByWindow = new LinkedHashMap<>();
        for (Integer k : windows) {
            nonNullReturnsByWindow.put(k, new ArrayList<>());
        }

        List<EventResult> events = new ArrayList<>(dyadEvents.size());
        for (DyadEventProjection event : dyadEvents) {
            Map<Integer, BigDecimal> perWindowReturns = new LinkedHashMap<>();
            for (Integer k : windows) {
                BasketForwardReturn result = forwardReturnService.basketForwardReturn(request.basket(), event.getEventDate(), k);
                BigDecimal r = result.averageReturn();
                perWindowReturns.put(k, r);
                if (r != null) {
                    nonNullReturnsByWindow.get(k).add(r);
                }
            }
            events.add(new EventResult(event.getEventDate(), event.getActor1CountryCode(), event.getActor2CountryCode(),
                    event.getArticleCount(), perWindowReturns));
        }

        List<WindowSummary> summary = new ArrayList<>();
        for (Integer k : windows) {
            summary.add(summarizeWindow(k, nonNullReturnsByWindow.get(k), dyadEvents.size()));
        }

        return new EventStudyResponse(dyadEvents.size(), summary, events);
    }

    /**
     * n is the count of events with a non-null basket return for this window - the real sample
     * size, not the raw article count or the distinct-event count. meanReturn/hitRate are null
     * (not 0) when n is 0, matching ForwardReturnService's "no data" convention.
     */
    private WindowSummary summarizeWindow(int windowDays, List<BigDecimal> nonNullReturns, int distinctEventCount) {
        int n = nonNullReturns.size();
        BigDecimal meanReturn = null;
        BigDecimal hitRate = null;
        if (n > 0) {
            BigDecimal sum = nonNullReturns.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            meanReturn = sum.divide(BigDecimal.valueOf(n), AGG_MATH_CONTEXT);
            long positiveCount = nonNullReturns.stream().filter(r -> r.compareTo(BigDecimal.ZERO) > 0).count();
            hitRate = BigDecimal.valueOf(positiveCount).divide(BigDecimal.valueOf(n), AGG_MATH_CONTEXT);
        }
        BigDecimal coverage = distinctEventCount == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(n).divide(BigDecimal.valueOf(distinctEventCount), AGG_MATH_CONTEXT);
        return new WindowSummary(windowDays, n, meanReturn, hitRate, coverage);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}