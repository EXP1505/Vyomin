package com.vyomin.core_api.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "price_daily")
@IdClass(PriceDailyId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceDaily {

    @Id
    @Column(name = "ticker", length = 16, nullable = false)
    private String ticker;

    @Id
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "open", precision = 12, scale = 4)
    private BigDecimal open;

    @Column(name = "high", precision = 12, scale = 4)
    private BigDecimal high;

    @Column(name = "low", precision = 12, scale = 4)
    private BigDecimal low;

    @Column(name = "close", precision = 12, scale = 4, nullable = false)
    private BigDecimal close;

    @Column(name = "volume")
    private Long volume;
}