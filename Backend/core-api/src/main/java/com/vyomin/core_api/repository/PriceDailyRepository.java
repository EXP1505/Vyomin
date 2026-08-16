package com.vyomin.core_api.repository;

import com.vyomin.core_api.model.PriceDaily;
import com.vyomin.core_api.model.PriceDailyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface PriceDailyRepository extends JpaRepository<PriceDaily, PriceDailyId> {

    List<PriceDaily> findByTickerOrderByTradeDateAsc(String ticker);

    List<PriceDaily> findByTickerAndTradeDateBetweenOrderByTradeDateAsc(String ticker, LocalDate from, LocalDate to);

    // Native upsert instead of save() so re-running a backfill is a plain idempotent write
    // (INSERT .. ON CONFLICT DO UPDATE) rather than a select-then-insert/update per row.
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO price_daily (ticker, trade_date, open, high, low, close, volume)
            VALUES (:ticker, :tradeDate, :open, :high, :low, :close, :volume)
            ON CONFLICT (ticker, trade_date) DO UPDATE SET
                open = EXCLUDED.open,
                high = EXCLUDED.high,
                low = EXCLUDED.low,
                close = EXCLUDED.close,
                volume = EXCLUDED.volume
            """, nativeQuery = true)
    void upsert(String ticker, LocalDate tradeDate, BigDecimal open, BigDecimal high, BigDecimal low,
                BigDecimal close, Long volume);
}