package com.vyomin.core_api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vyomin.core_api.service.FinanceService;
import com.vyomin.core_api.service.FinanceService.StockQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/intel/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final FinanceService financeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/trending")
    public ResponseEntity<?> trending() {
        try {
            List<String> symbols = financeService.getTrendingSymbols();
            List<StockQuote> quotes = symbols.stream()
                    .map(financeService::quoteForSymbol)
                    .toList();
            return ResponseEntity.ok(Map.of("type", "trending", "data", quotes));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("symbol") String symbol) {
        try {
            StockQuote quote = financeService.quoteForSymbol(symbol);
            return ResponseEntity.ok(Map.of("type", "search", "data", quote));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/profile")
    public ResponseEntity<String> profile(@RequestParam("symbol") String symbol) {
        try {
            JsonNode profile = financeService.profileForSymbol(symbol);
            ObjectNode res = objectMapper.createObjectNode();
            res.put("type", "profile");
            res.set("data", profile);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(res));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @GetMapping("/candles")
    public ResponseEntity<String> candles(@RequestParam("symbol") String symbol) {
        try {
            JsonNode candles = financeService.candlesForSymbol(symbol);
            ObjectNode res = objectMapper.createObjectNode();
            res.put("type", "candles");
            res.set("data", candles);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(res));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @GetMapping("/news")
    public ResponseEntity<String> news(@RequestParam("symbol") String symbol) {
        try {
            JsonNode news = financeService.newsForSymbol(symbol);
            ObjectNode res = objectMapper.createObjectNode();
            res.put("type", "news");
            res.set("data", news);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(res));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}