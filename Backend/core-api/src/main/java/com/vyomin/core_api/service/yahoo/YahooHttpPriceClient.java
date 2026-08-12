package com.vyomin.core_api.service.yahoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyomin.core_api.service.stooq.StooqPriceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Sources daily OHLCV from Yahoo Finance's unofficial v8 chart endpoint. Stooq now blocks plain
 * HTTP clients behind a JS proof-of-work challenge, so this is the active StooqPriceClient
 * implementation (@Primary) while StooqHttpPriceClient stays in the codebase unused in case Stooq
 * becomes reachable again.
 *
 * Yahoo's response is JSON, not CSV - fetchDailyCsv() converts it into the same
 * "Date,Open,High,Low,Close,Volume" CSV shape Stooq produced, so PriceBackfillService's existing
 * parsing/validation logic doesn't need to know which source it's talking to.
 */
@Component
@Primary
@Slf4j
public class YahooHttpPriceClient implements StooqPriceClient {

    private static final int TIMEOUT_MS = 15_000;
    private static final String BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final String CSV_HEADER = "Date,Open,High,Low,Close,Volume\n";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = buildRestClient();

    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String fetchDailyCsv(String ticker, LocalDate start, LocalDate end) {
        // period2 is exclusive of the current instant on the end day, so push it to the next
        // UTC midnight to make sure `end` itself is fully included.
        long period1 = start.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long period2 = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String url = String.format(Locale.ROOT,
                "%s/%s?period1=%d&period2=%d&interval=1d&events=div,splits",
                BASE_URL, ticker, period1, period2);
        log.info("Fetching Yahoo Finance daily chart for {}: {}", ticker, url);

        String body = restClient.get()
                .uri(url)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .retrieve()
                .body(String.class);

        return toCsv(ticker, body);
    }

    private String toCsv(String ticker, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("empty response from Yahoo Finance for " + ticker);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "non-JSON response from Yahoo Finance for " + ticker + " (likely rate limited or blocked): "
                            + e.getMessage(), e);
        }

        JsonNode chart = root.path("chart");
        JsonNode error = chart.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IllegalStateException("Yahoo Finance returned an error for " + ticker + ": " + error);
        }

        JsonNode results = chart.path("result");
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("Yahoo Finance returned no result for " + ticker);
        }

        JsonNode result = results.get(0);
        JsonNode timestamps = result.path("timestamp");
        JsonNode quotes = result.path("indicators").path("quote");
        if (!timestamps.isArray() || !quotes.isArray() || quotes.isEmpty()) {
            throw new IllegalStateException("Yahoo Finance response for " + ticker + " is missing timestamp/quote data");
        }

        JsonNode quote = quotes.get(0);
        JsonNode opens = quote.path("open");
        JsonNode highs = quote.path("high");
        JsonNode lows = quote.path("low");
        JsonNode closes = quote.path("close");
        JsonNode volumes = quote.path("volume");

        StringBuilder csv = new StringBuilder(CSV_HEADER);
        for (int i = 0; i < timestamps.size(); i++) {
            JsonNode closeNode = closes.get(i);
            if (closeNode == null || closeNode.isNull()) {
                continue;
            }
            LocalDate tradeDate = Instant.ofEpochSecond(timestamps.get(i).asLong()).atZone(ZoneOffset.UTC).toLocalDate();
            csv.append(tradeDate).append(',')
                    .append(numberOrBlank(opens, i)).append(',')
                    .append(numberOrBlank(highs, i)).append(',')
                    .append(numberOrBlank(lows, i)).append(',')
                    .append(closeNode.asText()).append(',')
                    .append(numberOrBlank(volumes, i)).append('\n');
        }
        return csv.toString();
    }

    private String numberOrBlank(JsonNode array, int index) {
        JsonNode node = array.get(index);
        return (node == null || node.isNull()) ? "" : node.asText();
    }
}