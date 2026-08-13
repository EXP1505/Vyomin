package com.vyomin.core_api.service;

import com.vyomin.core_api.model.PriceDaily;
import com.vyomin.core_api.repository.PriceDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.lenient;

/**
 * Standalone unit test for ForwardReturnService - no Spring context, PriceDailyRepository is
 * mocked. The trading-day calendar is the REAL SPY trade_date list from price_daily (exported to
 * src/test/resources/spy_trading_days.txt), and the close prices asserted on below were queried
 * from the actual database before writing this test:
 *
 *   SELECT trade_date, close FROM price_daily WHERE ticker='SPY'
 *   AND trade_date BETWEEN '2026-06-25' AND '2026-07-20' ORDER BY trade_date;
 *
 *   2026-07-01 | 745.76      2026-07-08 | 745.40
 *   2026-07-02 | 744.78      2026-07-09 | 751.71
 *   2026-07-06 | 751.28      2026-07-10 | 754.95
 *   2026-07-07 | 747.71      2026-07-13 | 749.17
 *
 *   SELECT trade_date, close FROM price_daily WHERE ticker='SPY' ORDER BY trade_date DESC LIMIT 1;
 *   2026-08-11 | 770.56  (the last trading day in price_daily)
 *
 * From that trading-day sequence, 2026-07-01's next few trading days are 07-02, 07-06, 07-07,
 * 07-08, 07-09 (07-03/04/05 are a market holiday + weekend) - so t0+1=07-02, t0+3=07-07, t0+5=07-09.
 */
@ExtendWith(MockitoExtension.class)
class ForwardReturnServiceTest {

    private static final double EPSILON = 1e-6;

    @Mock
    private PriceDailyRepository priceDailyRepository;

    private ForwardReturnService forwardReturnService;

    private static final Map<LocalDate, BigDecimal> KNOWN_SPY_CLOSES = Map.ofEntries(
            Map.entry(LocalDate.parse("2026-07-01"), new BigDecimal("745.76")),
            Map.entry(LocalDate.parse("2026-07-02"), new BigDecimal("744.78")),
            Map.entry(LocalDate.parse("2026-07-06"), new BigDecimal("751.28")),
            Map.entry(LocalDate.parse("2026-07-07"), new BigDecimal("747.71")),
            Map.entry(LocalDate.parse("2026-07-08"), new BigDecimal("745.40")),
            Map.entry(LocalDate.parse("2026-07-09"), new BigDecimal("751.71")),
            Map.entry(LocalDate.parse("2026-07-10"), new BigDecimal("754.95")),
            Map.entry(LocalDate.parse("2026-07-13"), new BigDecimal("749.17")),
            Map.entry(LocalDate.parse("2026-08-11"), new BigDecimal("770.56"))
    );

    @BeforeEach
    void setUp() throws IOException {
        List<LocalDate> tradingDays = loadRealSpyTradingDays();

        List<PriceDaily> spyRows = tradingDays.stream()
                .map(date -> PriceDaily.builder()
                        .ticker("SPY")
                        .tradeDate(date)
                        .close(KNOWN_SPY_CLOSES.getOrDefault(date, BigDecimal.TEN))
                        .build())
                .toList();

        // lenient(): not every test below exercises both stubs, and MockitoExtension's default
        // strict-stubs mode would otherwise fail tests that only need one of them.
        lenient().when(priceDailyRepository.findByTickerOrderByTradeDateAsc("SPY")).thenReturn(spyRows);
        lenient().when(priceDailyRepository.findByTickerOrderByTradeDateAsc("ZZZZ")).thenReturn(List.of());

        forwardReturnService = new ForwardReturnService(priceDailyRepository);
    }

    /** Loads the real SPY trade_date list (501 real trading days, 2024-08-12..2026-08-11). */
    private List<LocalDate> loadRealSpyTradingDays() throws IOException {
        List<LocalDate> dates = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("spy_trading_days.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    dates.add(LocalDate.parse(line.trim()));
                }
            }
        }
        return dates;
    }

    // --- Main case: 2026-07-01 is itself a trading day (Wednesday) ---

    @Test
    void forwardReturn_oneTradingDay_matchesHandComputedValue() {
        // t0 = 2026-07-01 (745.76), t0+1 = 2026-07-02 (744.78)
        // hand-computed: 744.78 / 745.76 - 1 = -0.0013140957
        BigDecimal actual = forwardReturnService.forwardReturn("SPY", LocalDate.parse("2026-07-01"), 1);
        assertThat(actual).isNotNull();
        assertThat(actual.doubleValue()).isCloseTo(-0.0013140957, within(EPSILON));
    }

    @Test
    void forwardReturn_threeTradingDays_matchesHandComputedValue() {
        // t0 = 2026-07-01 (745.76), t0+3 = 2026-07-07 (747.71)
        // hand-computed: 747.71 / 745.76 - 1 = 0.0026147822
        BigDecimal actual = forwardReturnService.forwardReturn("SPY", LocalDate.parse("2026-07-01"), 3);
        assertThat(actual).isNotNull();
        assertThat(actual.doubleValue()).isCloseTo(0.0026147822, within(EPSILON));
    }

    @Test
    void forwardReturn_fiveTradingDays_matchesHandComputedValue() {
        // t0 = 2026-07-01 (745.76), t0+5 = 2026-07-09 (751.71)
        // hand-computed: 751.71 / 745.76 - 1 = 0.0079784381
        BigDecimal actual = forwardReturnService.forwardReturn("SPY", LocalDate.parse("2026-07-01"), 5);
        assertThat(actual).isNotNull();
        assertThat(actual.doubleValue()).isCloseTo(0.0079784381, within(EPSILON));
    }

    // --- Weekend alignment ---

    @Test
    void forwardReturn_weekendEventDate_resolvesToPriorFridayTradingDay() {
        LocalDate saturday = LocalDate.parse("2026-07-11");
        assertThat(saturday.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);

        // t0 = last trading day <= 2026-07-11 = 2026-07-10 (754.95); t0+1 = 2026-07-13 (749.17)
        // hand-computed: 749.17 / 754.95 - 1 = -0.0076561362
        BigDecimal actual = forwardReturnService.forwardReturn("SPY", saturday, 1);
        assertThat(actual).isNotNull();
        assertThat(actual.doubleValue()).isCloseTo(-0.0076561362, within(EPSILON));
    }

    @Test
    void forwardReturn_marketHolidayFriday_resolvesToPriorThursdayTradingDay() {
        // 2026-07-03 is a Friday, but NYSE observes the July 4th holiday on it (since July 4 2026
        // falls on a Saturday) - it's not a trading day despite being a weekday, so t0 must fall
        // back to 2026-07-02, not sit on 07-03 itself.
        LocalDate holidayFriday = LocalDate.parse("2026-07-03");
        assertThat(holidayFriday.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);

        // t0 = last trading day <= 2026-07-03 = 2026-07-02 (744.78); t0+1 = 2026-07-06 (751.28)
        // hand-computed: 751.28 / 744.78 - 1 = 0.0087274094
        BigDecimal actual = forwardReturnService.forwardReturn("SPY", holidayFriday, 1);
        assertThat(actual).isNotNull();
        assertThat(actual.doubleValue()).isCloseTo(0.0087274094, within(EPSILON));
    }

    // --- Null cases: no window / no data, never a fake 0% ---

    @Test
    void forwardReturn_eventBeforePriceHistory_returnsNull() {
        // price_daily's earliest trading day is 2024-08-12; this predates it entirely.
        BigDecimal actual = forwardReturnService.forwardReturn("SPY", LocalDate.parse("2024-01-01"), 1);
        assertThat(actual).isNull();
    }

    @Test
    void forwardReturn_notEnoughTradingDaysRemaining_returnsNull() {
        // 2026-08-11 is the LAST trading day in price_daily - zero trading days remain after it,
        // so a 5-trading-day-forward window doesn't exist yet.
        BigDecimal actual = forwardReturnService.forwardReturn("SPY", LocalDate.parse("2026-08-11"), 5);
        assertThat(actual).isNull();
    }

    @Test
    void forwardReturn_missingTicker_returnsNull() {
        BigDecimal actual = forwardReturnService.forwardReturn("ZZZZ", LocalDate.parse("2026-07-01"), 1);
        assertThat(actual).isNull();
    }

    // --- Basket average ---

    @Test
    void basketForwardReturn_skipsMissingTickerAndReportsCoverage() {
        BasketForwardReturn result = forwardReturnService.basketForwardReturn(
                List.of("SPY", "ZZZZ"), LocalDate.parse("2026-07-01"), 1);

        assertThat(result.contributingCount()).isEqualTo(1);
        assertThat(result.requestedCount()).isEqualTo(2);
        assertThat(result.averageReturn()).isNotNull();
        assertThat(result.averageReturn().doubleValue()).isCloseTo(-0.0013140957, within(EPSILON));
    }

    @Test
    void basketForwardReturn_noContributingTickers_returnsNullAverage() {
        BasketForwardReturn result = forwardReturnService.basketForwardReturn(
                List.of("ZZZZ"), LocalDate.parse("2026-07-01"), 1);

        assertThat(result.contributingCount()).isEqualTo(0);
        assertThat(result.requestedCount()).isEqualTo(1);
        assertThat(result.averageReturn()).isNull();
    }
}