package com.vyomin.core_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves a free-text company name to Stooq-compatible ticker symbols so the Event Study
 * basket field can be a name search instead of requiring the user already know the ticker.
 *
 * Backed by Yahoo Finance's public (undocumented but widely-used, free, no API key) search
 * endpoint - there's no free, no-key alternative with comparable coverage. It's a genuinely
 * different host/dependency from Stooq (which still supplies the actual price history), so a
 * change or outage on Yahoo's side only breaks the name-search convenience, not price data
 * already in Postgres or newly on-demand-fetched via PriceBackfillService.
 */
@Service
@Slf4j
public class TickerSearchService {

    private static final String SEARCH_URL = "https://query2.finance.yahoo.com/v1/finance/search";
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_RESULTS = 8;
    // EQUITY covers ordinary stocks; ETF covers index/commodity funds like the SPY/USO already in
    // the default basket. Everything else Yahoo's search returns (mutual funds, currencies,
    // crypto, futures) isn't something Stooq's daily-bar backfill is meant to serve here.
    private static final Set<String> ALLOWED_QUOTE_TYPES = Set.of("EQUITY", "ETF");

    private final RestClient restClient = buildRestClient();

    public record TickerSearchResult(String symbol, String name, String exchange) {
    }

    public List<TickerSearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String uri = UriComponentsBuilder.fromUriString(SEARCH_URL)
                .queryParam("q", query.trim())
                .queryParam("quotesCount", MAX_RESULTS)
                .queryParam("newsCount", 0)
                .toUriString();

        try {
            // Fetching as raw text first (not straight to JsonNode) so a non-JSON response -
            // Yahoo returning an HTML challenge/block page instead of the expected body, which a
            // direct JsonNode conversion would just silently fail to populate from - is visible
            // in the logs instead of indistinguishable from a legitimately empty result.
            String rawBody = restClient.get().uri(uri).retrieve().body(String.class);
            if (rawBody == null || rawBody.isBlank()) {
                log.warn("Ticker search for query='{}' got an empty response body", query);
                return List.of();
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root;
            try {
                root = mapper.readTree(rawBody);
            } catch (Exception parseEx) {
                log.warn("Ticker search for query='{}' returned non-JSON (likely a block/challenge page), first 200 chars: {}",
                        query, rawBody.substring(0, Math.min(200, rawBody.length())));
                return List.of();
            }

            JsonNode quotes = root.path("quotes");
            log.info("Ticker search for query='{}': {} raw quote(s) before filtering", query, quotes.size());
            List<TickerSearchResult> results = new ArrayList<>();
            for (JsonNode quote : quotes) {
                String quoteType = quote.path("quoteType").asText("");
                String symbol = quote.path("symbol").asText("");
                if (symbol.isBlank() || !ALLOWED_QUOTE_TYPES.contains(quoteType)) {
                    continue;
                }
                // Yahoo distinguishes shortname/longname; prefer whichever is actually present
                // rather than assuming both are always populated.
                String name = quote.path("longname").asText(null);
                if (name == null || name.isBlank()) {
                    name = quote.path("shortname").asText(symbol);
                }
                String exchange = quote.path("exchange").asText("");
                results.add(new TickerSearchResult(symbol, name, exchange));
            }
            return results;
        } catch (Exception e) {
            // Convenience feature only - a failed lookup shouldn't break the page, it just means
            // the user falls back to typing a ticker directly like before.
            log.warn("Ticker search failed for query='{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        return RestClient.builder()
                .requestFactory(factory)
                // Same reasoning as GdeltIngestionService's client - Yahoo's search endpoint
                // returns empty/blocked responses for requests that look like a bare JVM client.
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (compatible; VyominBot/1.0; +https://vyomin-web.onrender.com)")
                .build();
    }
}
