package com.vyomin.core_api.service.stooq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
@Slf4j
public class StooqHttpPriceClient implements StooqPriceClient {

    private static final int TIMEOUT_MS = 15_000;
    private static final DateTimeFormatter STOOQ_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Value("${stooq.base-url}")
    private String baseUrl;

    private final RestClient restClient = buildRestClient();

    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String fetchDailyCsv(String ticker, LocalDate start, LocalDate end) {
        String url = String.format(Locale.ROOT, "%s/q/d/l/?s=%s.us&i=d&d1=%s&d2=%s",
                baseUrl, ticker.toLowerCase(Locale.ROOT), start.format(STOOQ_DATE_FORMAT), end.format(STOOQ_DATE_FORMAT));
        log.info("Fetching Stooq daily CSV for {}: {}", ticker, url);
        return restClient.get().uri(url).retrieve().body(String.class);
    }
}