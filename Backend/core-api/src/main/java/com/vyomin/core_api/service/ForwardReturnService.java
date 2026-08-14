package com.vyomin.core_api.service;

import com.vyomin.core_api.model.PriceDaily;
import com.vyomin.core_api.repository.PriceDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Computes forward returns of stocks after an event date, aligned to trading days rather than
 * calendar days - the conceptual core of the event-study analyzer. Standalone: no batch endpoint
 * or controller wiring here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ForwardReturnService {

    // SPY has a row in price_daily for every trading day the whole ticker set shares, so its
    // trade_date set doubles as the trading-day calendar - avoids needing a separate calendar
    // source just to know which calendar dates markets were actually open.
    private static final String TRADING_CALENDAR_TICKER = "SPY";
    private static final MathContext RETURN_MATH_CONTEXT = new MathContext(10, RoundingMode.HALF_UP);

    private final PriceDailyRepository priceDailyRepository;

    private volatile List<LocalDate> tradingDaysCache;
    private final Map<String, Map<LocalDate, BigDecimal>> closesByTickerCache = new ConcurrentHashMap<>();

    /**
     * Forward return of `ticker` over k trading days from eventDate:
     *   t0 = last trading day &lt;= eventDate (SPY's trade_date calendar)
     *   tk = the trading day k positions after t0
     *   r_k = close[tk] / close[t0] - 1
     *
     * Returns null - never 0, never an exception - when there's no valid window or price data:
     * eventDate is before the trading calendar starts, fewer than k trading days remain after t0,
     * or a close is missing for `ticker` on t0 or tk. Callers must not treat null as a 0% return -
     * it means "no answer," not "no change."
     */
    public BigDecimal forwardReturn(String ticker, LocalDate eventDate, int k) {
        List<LocalDate> tradingDays = tradingDays();
        int t0Index = lastTradingDayIndexOnOrBefore(tradingDays, eventDate);
        if (t0Index < 0) {
            log.debug("forwardReturn({}, {}, {}): eventDate is before the trading calendar starts", ticker, eventDate, k);
            return null;
        }

        int tkIndex = t0Index + k;
        if (tkIndex >= tradingDays.size()) {
            log.debug("forwardReturn({}, {}, {}): only {} trading days remain after t0={}, need {}",
                    ticker, eventDate, k, tradingDays.size() - 1 - t0Index, tradingDays.get(t0Index), k);
            return null;
        }

        LocalDate t0 = tradingDays.get(t0Index);
        LocalDate tk = tradingDays.get(tkIndex);

        Map<LocalDate, BigDecimal> closes = closesForTicker(ticker);
        BigDecimal closeT0 = closes.get(t0);
        BigDecimal closeTk = closes.get(tk);
        if (closeT0 == null || closeTk == null || closeT0.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("forwardReturn({}, {}, {}): missing close for t0={} ({}) or tk={} ({})",
                    ticker, eventDate, k, t0, closeT0, tk, closeTk);
            return null;
        }

        return closeTk.divide(closeT0, RETURN_MATH_CONTEXT).subtract(BigDecimal.ONE);
    }

    /**
     * The trading-day baseline t0 that forwardReturn() resolves internally: the last trading day
     * &lt;= eventDate (SPY's trade_date calendar). Exposed so callers that need to cluster/dedupe by
     * baseline (e.g. EventStudyService, which otherwise can't tell two different event dates
     * resolve to the same t0) don't have to re-implement this lookup themselves.
     *
     * Returns null under the same condition forwardReturn() does: eventDate is before the trading
     * calendar starts.
     */
    public LocalDate resolveBaselineTradingDay(LocalDate eventDate) {
        List<LocalDate> tradingDays = tradingDays();
        int t0Index = lastTradingDayIndexOnOrBefore(tradingDays, eventDate);
        return t0Index < 0 ? null : tradingDays.get(t0Index);
    }

    /**
     * Equal-weight average of forwardReturn() across tickers for the same event and horizon,
     * skipping tickers that return null. contributingCount lets callers tell "nobody had data"
     * apart from "the basket's return happened to be low."
     */
    public BasketForwardReturn basketForwardReturn(List<String> tickers, LocalDate eventDate, int k) {
        List<BigDecimal> contributing = new ArrayList<>();
        for (String ticker : tickers) {
            BigDecimal r = forwardReturn(ticker, eventDate, k);
            if (r != null) {
                contributing.add(r);
            }
        }

        if (contributing.isEmpty()) {
            return new BasketForwardReturn(null, 0, tickers.size());
        }

        BigDecimal sum = contributing.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = sum.divide(BigDecimal.valueOf(contributing.size()), RETURN_MATH_CONTEXT);
        return new BasketForwardReturn(average, contributing.size(), tickers.size());
    }

    private List<LocalDate> tradingDays() {
        List<LocalDate> cached = tradingDaysCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (tradingDaysCache == null) {
                List<LocalDate> loaded = priceDailyRepository.findByTickerOrderByTradeDateAsc(TRADING_CALENDAR_TICKER).stream()
                        .map(PriceDaily::getTradeDate)
                        .toList();
                log.info("Loaded {} trading days from {}'s price history ({} to {})", loaded.size(), TRADING_CALENDAR_TICKER,
                        loaded.isEmpty() ? null : loaded.get(0), loaded.isEmpty() ? null : loaded.get(loaded.size() - 1));
                tradingDaysCache = loaded;
            }
            return tradingDaysCache;
        }
    }

    private Map<LocalDate, BigDecimal> closesForTicker(String ticker) {
        return closesByTickerCache.computeIfAbsent(ticker, t -> priceDailyRepository.findByTickerOrderByTradeDateAsc(t).stream()
                .collect(Collectors.toMap(PriceDaily::getTradeDate, PriceDaily::getClose, (a, b) -> b)));
    }

    /** Index of the last trading day &lt;= target, via binary search; -1 if target precedes every trading day. */
    private int lastTradingDayIndexOnOrBefore(List<LocalDate> tradingDays, LocalDate target) {
        int idx = Collections.binarySearch(tradingDays, target);
        if (idx >= 0) {
            return idx;
        }
        int insertionPoint = -(idx + 1);
        return insertionPoint - 1;
    }
}