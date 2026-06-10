package com.vyomin.core_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class FinanceService {

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${finnhub.api.key}")
    private String finnhubApiKey;

    private static final String FINNHUB_BASE_URL = "https://finnhub.io/api/v1";

    private static final List<String> TRENDING_SYMBOLS = Arrays.asList(
            "AAPL", "NVDA", "TSLA", "MSFT"
    );

    public List<String> getTrendingSymbols() {
        return TRENDING_SYMBOLS;
    }

    public StockQuote quoteForSymbol(String rawSymbol) {
        String symbol = normalizeSymbol(rawSymbol);
        if (symbol == null) throw new IllegalArgumentException("symbol is required");

        String url = FINNHUB_BASE_URL + "/quote?symbol=" + symbol + "&token=" + finnhubApiKey;
        try {
            String response = restClient.get().uri(url).retrieve().body(String.class);
            if (response == null || response.isBlank()) throw new IllegalStateException("Empty response from Finnhub");

            JsonNode node = objectMapper.readTree(response);
            if (node.has("error")) throw new IllegalArgumentException(node.path("error").asText("Finnhub error"));

            double currentPrice = node.path("c").asDouble(Double.NaN);
            double percentChange = node.path("dp").asDouble(Double.NaN);
            double change = node.path("d").asDouble(0.0);

            return new StockQuote(symbol, currentPrice, change, percentChange);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Finnhub quote fetch failed for symbol={}", symbol, e);
            throw new IllegalStateException("Failed to fetch quote from Finnhub");
        }
    }

    public JsonNode profileForSymbol(String rawSymbol) {
        String symbol = normalizeSymbol(rawSymbol);
        if (symbol == null) throw new IllegalArgumentException("symbol is required");

        String url = FINNHUB_BASE_URL + "/stock/profile2?symbol=" + symbol + "&token=" + finnhubApiKey;
        try {
            String response = restClient.get().uri(url).retrieve().body(String.class);
            if (response == null || response.isBlank()) throw new IllegalStateException("Empty response from Finnhub");

            JsonNode node = objectMapper.readTree(response);
            if (node.has("error")) throw new IllegalArgumentException(node.path("error").asText("Finnhub error"));
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Finnhub profile fetch failed for symbol={}", symbol, e);
            throw new IllegalStateException("Failed to fetch profile from Finnhub");
        }
    }

    public JsonNode candlesForSymbol(String rawSymbol) {
        String symbol = normalizeSymbol(rawSymbol);
        if (symbol == null) throw new IllegalArgumentException("symbol is required");

        String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                + symbol + "?interval=1d&range=1mo";
        try {
            String response = restClient.get()
                    .uri(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode result = root.path("chart").path("result").get(0);

            if (result == null || result.isMissingNode())
                throw new IllegalStateException("No data returned from Yahoo Finance");

            JsonNode timestamps = result.path("timestamp");
            JsonNode quote = result.path("indicators").path("quote").get(0);

            ObjectNode out = objectMapper.createObjectNode();
            out.set("t", timestamps);
            out.set("o", quote.path("open"));
            out.set("h", quote.path("high"));
            out.set("l", quote.path("low"));
            out.set("c", quote.path("close"));
            out.put("s", "ok");
            return out;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Yahoo candle fetch failed for symbol={}", symbol, e);
            throw new IllegalStateException("Failed to fetch candles from Yahoo Finance");
        }
    }

    public JsonNode newsForSymbol(String rawSymbol) {
        String symbol = normalizeSymbol(rawSymbol);
        if (symbol == null) throw new IllegalArgumentException("symbol is required");

        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate weekAgo = today.minusDays(7);
        String url = FINNHUB_BASE_URL + "/company-news?symbol=" + symbol
                + "&from=" + weekAgo
                + "&to=" + today
                + "&token=" + finnhubApiKey;
        try {
            String response = restClient.get().uri(url).retrieve().body(String.class);
            if (response == null || response.isBlank()) throw new IllegalStateException("Empty response from Finnhub");

            JsonNode node = objectMapper.readTree(response);
            if (node.has("error")) throw new IllegalArgumentException(node.path("error").asText("Finnhub error"));
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Finnhub news fetch failed for symbol={}", symbol, e);
            throw new IllegalStateException("Failed to fetch news from Finnhub");
        }
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (!s.matches("^[A-Z0-9.-]{1,20}$")) throw new IllegalArgumentException("Invalid symbol format");
        return s;
    }

    public record StockQuote(String symbol, double currentPrice, double change, double percentChange) {}
}