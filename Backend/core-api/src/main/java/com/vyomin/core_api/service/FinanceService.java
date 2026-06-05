package com.vyomin.core_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    // Default Finnhub base URL. Can be overridden by env if needed.
    private static final String FINNHUB_BASE_URL = "https://finnhub.io/api/v1";

    private static final List<String> TRENDING_SYMBOLS = Arrays.asList(
            "AAPL", "NVDA", "TSLA", "MSFT"
    );

    public List<String> getTrendingSymbols() {
        return TRENDING_SYMBOLS;
    }

    public StockQuote quoteForSymbol(String rawSymbol) {
        String symbol = normalizeSymbol(rawSymbol);
        if (symbol == null) {
            throw new IllegalArgumentException("symbol is required");
        }

        // Finnhub: /quote?symbol=SYMBOL&token=...
        String url = FINNHUB_BASE_URL + "/quote?symbol=" + symbol + "&token=" + finnhubApiKey;

        try {
            String response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                throw new IllegalStateException("Empty response from Finnhub");
            }

            JsonNode node = objectMapper.readTree(response);

            // Finnhub quote response includes: c (current), d (change), dp (percent change)
            double currentPrice = node.path("c").asDouble(Double.NaN);
            double percentChange = node.path("dp").asDouble(Double.NaN);
            double change = node.path("d").asDouble(0.0);

            // If Finnhub returns an error, it usually includes 'error' message.
            if (node.has("error")) {
                throw new IllegalArgumentException(node.path("error").asText("Finnhub error"));
            }

            return new StockQuote(symbol, currentPrice, change, percentChange);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Finnhub quote fetch failed for symbol={}", symbol, e);
            throw new IllegalStateException("Failed to fetch quote from Finnhub");
        }
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        // Allow letters/numbers only.
        if (!s.matches("^[A-Z0-9.-]{1,20}$")) {
            throw new IllegalArgumentException("Invalid symbol format");
        }
        return s;
    }

    public record StockQuote(String symbol, double currentPrice, double change, double percentChange) {
    }
}

