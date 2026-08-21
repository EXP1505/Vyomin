package com.vyomin.core_api.controller;

import com.vyomin.core_api.dto.EventStudyDossierDtos.CountryDossierResponse;
import com.vyomin.core_api.dto.EventStudyDtos.EventStudyRequest;
import com.vyomin.core_api.dto.EventStudyDtos.EventStudyResponse;
import com.vyomin.core_api.dto.EventStudyDtos.PriceHistoryPoint;
import com.vyomin.core_api.dto.EventStudySweepDtos.EventStudySweepRequest;
import com.vyomin.core_api.dto.EventStudySweepDtos.EventStudySweepResponse;
import com.vyomin.core_api.model.PriceDaily;
import com.vyomin.core_api.repository.PriceDailyRepository;
import com.vyomin.core_api.service.EventStudyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@Slf4j
public class EventStudyController {

    // Same broad, non-specialist basket used as the frontend's default event-study basket - the
    // dossier falls back to it when the caller doesn't supply one.
    private static final List<String> DEFAULT_BASKET = List.of("LMT", "RTX", "NOC", "GD", "BA");
    private static final String DOSSIER_CACHE_PREFIX = "country-dossier:";
    private static final Duration DOSSIER_CACHE_TTL = Duration.ofMinutes(15);

    private final EventStudyService eventStudyService;
    private final PriceDailyRepository priceDailyRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper redisObjectMapper;

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

    @GetMapping("/country-dossier")
    public ResponseEntity<CountryDossierResponse> countryDossier(@RequestParam String code,
                                                                   @RequestParam LocalDate dateFrom,
                                                                   @RequestParam LocalDate dateTo,
                                                                   @RequestParam(required = false) String basket) {
        String countryCode = code.toUpperCase();
        List<String> basketList = (basket == null || basket.isBlank())
                ? DEFAULT_BASKET
                : Arrays.stream(basket.split(",")).map(String::trim).map(String::toUpperCase).filter(s -> !s.isEmpty()).toList();

        String cacheKey = DOSSIER_CACHE_PREFIX + countryCode + ":" + dateFrom + ":" + dateTo + ":" + String.join(",", basketList);
        // GenericJackson2JsonRedisSerializer round-trips a stored value as raw
        // LinkedHashMap/List/etc structure (no embedded type info), not the original record type -
        // convertValue re-hydrates it into CountryDossierResponse using the same JavaTimeModule-
        // equipped mapper that wrote it, so nested LocalDate fields deserialize correctly.
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return ResponseEntity.ok(redisObjectMapper.convertValue(cached, CountryDossierResponse.class));
        }

        CountryDossierResponse response = eventStudyService.runCountryDossier(countryCode, dateFrom, dateTo, basketList);
        redisTemplate.opsForValue().set(cacheKey, response, DOSSIER_CACHE_TTL);
        return ResponseEntity.ok(response);
    }
}