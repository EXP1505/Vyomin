package com.vyomin.core_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyomin.core_api.model.intelligencegraph.Company;
import com.vyomin.core_api.model.intelligencegraph.Conflict;
import com.vyomin.core_api.model.intelligencegraph.Country;
import com.vyomin.core_api.model.intelligencegraph.Sector;
import com.vyomin.core_api.repository.intelligencegraph.CompanyRepository;
import com.vyomin.core_api.repository.intelligencegraph.ConflictRepository;
import com.vyomin.core_api.repository.intelligencegraph.CountryRepository;
import com.vyomin.core_api.repository.intelligencegraph.SectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills gaps in AuraDB by fetching data from external sources (GDELT, Yahoo Finance) whenever
 * a search finds nothing cached. Runs off the request thread and notifies the frontend over
 * the /topic/data-fetch/{requestId} STOMP destination when it's done.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncDataFetchService {

    private static final String YAHOO_QUOTE_SUMMARY_URL =
            "https://query2.finance.yahoo.com/v10/finance/quoteSummary/%s?modules=summaryProfile";
    private static final int YAHOO_TIMEOUT_MS = 30_000;

    private final GdeltIngestionService gdeltIngestionService;
    private final CompanyRepository companyRepository;
    private final SectorRepository sectorRepository;
    private final CountryRepository countryRepository;
    private final ConflictRepository conflictRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final RestClient restClient = buildTimeoutBoundRestClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static RestClient buildTimeoutBoundRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(YAHOO_TIMEOUT_MS);
        factory.setReadTimeout(YAHOO_TIMEOUT_MS);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Async
    public void fetchMissingData(String query, String searchType, String requestId) {
        log.info("Starting async fetch for query='{}', type='{}', requestId='{}'", query, searchType, requestId);
        try {
            switch (searchType) {
                // fetchConflictData notifies the frontend itself (it needs to report a result
                // count either way), so it's excluded from the generic notify below.
                case "conflict" -> {
                    fetchConflictData(query, requestId);
                    return;
                }
                case "country" -> fetchCountryConflicts(query);
                case "company" -> fetchCompanyData(query);
                default -> log.info("No external data source configured for type='{}', skipping fetch", searchType);
            }

            log.info("All done, notifying frontend for requestId='{}'", requestId);
            notifyFrontend(requestId, true, null);
        } catch (Exception e) {
            log.error("Error fetching data for requestId='{}'", requestId, e);
            notifyFrontend(requestId, false, e.getMessage());
        }
    }

    private void fetchCountryConflicts(String countryName) {
        try {
            log.info("Fetching from GDELT for country='{}'...", countryName);
            Map<String, Object> result = gdeltIngestionService.fetchAndIngestForQuery(countryName);
            log.info("GDELT fetch for country='{}' complete: {}", countryName, result);
        } catch (Exception e) {
            log.error("GDELT fetch failed for country='{}': {}", countryName, e.getMessage(), e);
            throw e;
        }
    }

    private void fetchConflictData(String conflictQuery, String requestId) {
        try {
            log.info("Fetching conflicts for query='{}'", conflictQuery);
            List<Conflict> matches = conflictRepository.findByDescriptionContainingIgnoreCase(conflictQuery);

            if (matches.isEmpty()) {
                log.info("No conflicts found");
                notifyFrontend(requestId, true, null, 0);
                return;
            }

            log.info("Found {} conflicts", matches.size());
            notifyFrontend(requestId, true, null, matches.size());
        } catch (Exception e) {
            log.error("Conflict lookup failed for query='{}': {}", conflictQuery, e.getMessage(), e);
            notifyFrontend(requestId, false, e.getMessage(), 0);
        }
    }

    private void fetchCompanyData(String companyQuery) {
        String symbol = companyQuery.trim().toUpperCase();
        String url = String.format(YAHOO_QUOTE_SUMMARY_URL, symbol);

        ResponseEntity<String> response;
        try {
            response = restClient.get()
                    .uri(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .retrieve()
                    .toEntity(String.class);
        } catch (Exception e) {
            log.warn("Yahoo Finance request failed for symbol '{}': {}", symbol, e.getMessage());
            return;
        }

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.warn("Yahoo Finance returned {} for symbol '{}'", response.getStatusCode(), symbol);
            return;
        }

        JsonNode profile;
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            profile = root.path("quoteSummary").path("result").path(0).path("summaryProfile");
        } catch (Exception e) {
            log.warn("Failed to parse Yahoo Finance response for symbol '{}': {}", symbol, e.getMessage());
            return;
        }

        if (profile.isMissingNode()) {
            log.info("No Yahoo Finance summaryProfile found for symbol '{}'", symbol);
            return;
        }

        ingestCompanyProfile(symbol, profile);
    }

    @Transactional
    void ingestCompanyProfile(String symbol, JsonNode profile) {
        Company company = companyRepository.findByTicker(symbol).orElseGet(Company::new);
        company.setTicker(symbol);
        if (company.getName() == null || company.getName().isBlank()) {
            company.setName(symbol);
        }

        String industry = profile.path("industry").asText(null);
        if (industry != null && !industry.isBlank()) {
            company.setIndustry(industry);
        }

        String sectorName = profile.path("sector").asText(null);
        if (sectorName != null && !sectorName.isBlank()) {
            company.setSector(getOrCreateSector(sectorName));
        }

        String country = profile.path("country").asText(null);
        if (country != null && !country.isBlank()) {
            company.setHeadquarters(getOrCreateCountry(country));
        }

        String summary = profile.path("longBusinessSummary").asText(null);
        if (summary != null && !summary.isBlank()) {
            company.setDescription(summary);
        }

        companyRepository.save(company);
        log.info("Ingested Yahoo Finance profile for {}", symbol);
    }

    private Sector getOrCreateSector(String name) {
        return sectorRepository.findByName(name).orElseGet(() -> {
            Sector sector = new Sector();
            sector.setName(name);
            return sectorRepository.save(sector);
        });
    }

    private Country getOrCreateCountry(String name) {
        return countryRepository.findByName(name).orElseGet(() -> {
            Country country = new Country();
            country.setName(name);
            country.setRegion("Unknown");
            return countryRepository.save(country);
        });
    }

    private void notifyFrontend(String requestId, boolean success, String error) {
        notifyFrontend(requestId, success, error, null);
    }

    private void notifyFrontend(String requestId, boolean success, String error, Integer resultsFound) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("requestId", requestId);
        message.put("success", success);
        message.put("error", error);
        if (resultsFound != null) {
            message.put("resultsFound", resultsFound);
        }
        messagingTemplate.convertAndSend("/topic/data-fetch/" + requestId, (Object) message);
        log.info("Notified frontend for requestId='{}': success={}, resultsFound={}", requestId, success, resultsFound);
    }
}
