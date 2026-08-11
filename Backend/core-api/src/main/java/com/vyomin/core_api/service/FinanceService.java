package com.vyomin.core_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    // Curated large-cap universe used to compute "top movers" / "big companies struggling" since
    // Finnhub's screener endpoints require a paid plan.
    private static final List<String> BIG_CAP_SYMBOLS = Arrays.asList(
            "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA", "JPM", "V", "JNJ",
            "WMT", "PG", "XOM", "HD", "MA", "BAC", "KO", "PEP", "DIS", "NFLX"
    );

    private static final Map<String, String> MOVER_PERIOD_RANGE = Map.of(
            "week", "5d",
            "month", "1mo",
            "year", "1y"
    );

    private static final long MOVERS_CACHE_TTL_SECONDS = 600;
    private final Map<String, CachedMovers> moversCache = new ConcurrentHashMap<>();

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
        return candlesForSymbol(rawSymbol, "1mo");
    }

    public JsonNode candlesForSymbol(String rawSymbol, String range) {
        String symbol = normalizeSymbol(rawSymbol);
        if (symbol == null) throw new IllegalArgumentException("symbol is required");

        String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                + symbol + "?interval=1d&range=" + range;
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

    public JsonNode marketNews() {
        String url = FINNHUB_BASE_URL + "/news?category=general&token=" + finnhubApiKey;
        try {
            String response = restClient.get().uri(url).retrieve().body(String.class);
            if (response == null || response.isBlank()) throw new IllegalStateException("Empty response from Finnhub");

            JsonNode node = objectMapper.readTree(response);
            if (node.isObject() && node.has("error")) throw new IllegalStateException(node.path("error").asText("Finnhub error"));
            return node;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Finnhub market news fetch failed", e);
            throw new IllegalStateException("Failed to fetch market news from Finnhub");
        }
    }

    /**
     * Top gainers/losers among a curated big-cap universe for the given period.
     * "day" uses Finnhub's live quote change; other periods derive the change from Yahoo
     * daily candles over the matching range. Results are cached briefly since this fans out
     * to ~20 upstream requests.
     */
    public MoversResult getMovers(String period) {
        String key = (period == null) ? "day" : period.toLowerCase(Locale.ROOT);
        if (!key.equals("day") && !MOVER_PERIOD_RANGE.containsKey(key)) {
            throw new IllegalArgumentException("period must be one of day, week, month, year");
        }

        CachedMovers cached = moversCache.get(key);
        if (cached != null && Instant.now().getEpochSecond() - cached.fetchedAtEpochSecond() < MOVERS_CACHE_TTL_SECONDS) {
            return cached.result();
        }

        List<MoverQuote> quotes = BIG_CAP_SYMBOLS.parallelStream()
                .map(symbol -> {
                    try {
                        return key.equals("day") ? moverFromQuote(symbol) : moverFromCandles(symbol, MOVER_PERIOD_RANGE.get(key));
                    } catch (Exception e) {
                        log.warn("Skipping mover symbol={} period={}: {}", symbol, key, e.getMessage());
                        return null;
                    }
                })
                .filter(q -> q != null && !Double.isNaN(q.percentChange()))
                .toList();

        List<MoverQuote> gainers = quotes.stream()
                .sorted(Comparator.comparingDouble(MoverQuote::percentChange).reversed())
                .limit(5)
                .toList();
        List<MoverQuote> losers = quotes.stream()
                .sorted(Comparator.comparingDouble(MoverQuote::percentChange))
                .limit(5)
                .toList();

        MoversResult result = new MoversResult(gainers, losers);
        moversCache.put(key, new CachedMovers(result, Instant.now().getEpochSecond()));
        return result;
    }

    private MoverQuote moverFromQuote(String symbol) {
        StockQuote q = quoteForSymbol(symbol);
        return new MoverQuote(symbol, q.currentPrice(), q.percentChange());
    }

    private MoverQuote moverFromCandles(String symbol, String range) {
        JsonNode candles = candlesForSymbol(symbol, range);
        JsonNode closes = candles.path("c");
        double first = Double.NaN;
        double last = Double.NaN;
        for (JsonNode c : closes) {
            if (c.isNull()) continue;
            if (Double.isNaN(first)) first = c.asDouble();
            last = c.asDouble();
        }
        if (Double.isNaN(first) || Double.isNaN(last) || first == 0) {
            throw new IllegalStateException("Insufficient candle data for " + symbol);
        }
        double percentChange = (last - first) / first * 100.0;
        return new MoverQuote(symbol, last, percentChange);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (!s.matches("^[A-Z0-9.-]{1,20}$")) throw new IllegalArgumentException("Invalid symbol format");
        return s;
    }

    public record StockQuote(String symbol, double currentPrice, double change, double percentChange) {}

    public record MoverQuote(String symbol, double currentPrice, double percentChange) {}

    public record MoversResult(List<MoverQuote> gainers, List<MoverQuote> losers) {}

    private record CachedMovers(MoversResult result, long fetchedAtEpochSecond) {}
}