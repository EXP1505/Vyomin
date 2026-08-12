package com.vyomin.core_api.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceDailyId implements Serializable {
    private String ticker;
    private LocalDate tradeDate;
}