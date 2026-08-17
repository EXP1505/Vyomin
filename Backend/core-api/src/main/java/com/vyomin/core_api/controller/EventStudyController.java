package com.vyomin.core_api.controller;

import com.vyomin.core_api.dto.EventStudyDtos.EventStudyRequest;
import com.vyomin.core_api.dto.EventStudyDtos.EventStudyResponse;
import com.vyomin.core_api.dto.EventStudyDtos.PriceHistoryPoint;
import com.vyomin.core_api.dto.EventStudySweepDtos.EventStudySweepRequest;
import com.vyomin.core_api.dto.EventStudySweepDtos.EventStudySweepResponse;
import com.vyomin.core_api.model.PriceDaily;
import com.vyomin.core_api.repository.PriceDailyRepository;
import com.vyomin.core_api.service.EventStudyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@Slf4j
public class EventStudyController {

    private final EventStudyService eventStudyService;
    private final PriceDailyRepository priceDailyRepository;

    @PostMapping("/event-study")
    public ResponseEntity<EventStudyResponse> runEventStudy(@RequestBody EventStudyRequest request) {
        return ResponseEntity.ok(eventStudyService.runEventStudy(request));
    }

    @PostMapping("/event-study-sweep")
    public ResponseEntity<EventStudySweepResponse> runEventStudySweep(@RequestBody EventStudySweepRequest request) {
        return ResponseEntity.ok(eventStudyService.runSweep(request));
    }

    @GetMapping("/price-history")
    public ResponseEntity<List<PriceHistoryPoint>> priceHistory(@RequestParam String ticker,
                                                                  @RequestParam LocalDate from,
                                                                  @RequestParam LocalDate to) {
        List<PriceHistoryPoint> points = priceDailyRepository
                .findByTickerAndTradeDateBetweenOrderByTradeDateAsc(ticker.toUpperCase(), from, to).stream()
                .map(p -> new PriceHistoryPoint(p.getTradeDate(), p.getOpen(), p.getHigh(), p.getLow(), p.getClose(), p.getVolume()))
                .toList();
        return ResponseEntity.ok(points);
    }
}