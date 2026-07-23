package com.vyomin.core_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyomin.core_api.model.intelligencegraph.Company;
import com.vyomin.core_api.model.intelligencegraph.Country;
import com.vyomin.core_api.model.intelligencegraph.Sector;
import com.vyomin.core_api.repository.intelligencegraph.CompanyRepository;
import com.vyomin.core_api.repository.intelligencegraph.CountryRepository;
import com.vyomin.core_api.repository.intelligencegraph.SectorRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyIngestionService {

    private static final String FINNHUB_STOCK_LIST_URL = "https://finnhub.io/api/v1/stock/list";
    private static final String FINNHUB_PROFILE_URL = "https://finnhub.io/api/v1/stock/profile2";
    private static final List<String> TARGET_CATEGORIES = List.of("technology", "healthcare");
    private static final int MAX_COMPANIES = 100;
    private static final long PROFILE_CALL_DELAY_MS = 100;
    private static final int STACK_TRACE_LINE_LIMIT = 5;

    private static final Map<String, String> SECTOR_MAP = Map.of(
            "Technology", "semiconductors",
            "Industrials", "defense",
            "Aerospace & Defense", "defense",
            "Energy", "oil",
            "Materials", "rare-earths",
            "Real Estate", "real-estate"
    );

    private final CompanyRepository companyRepository;
    private final SectorRepository sectorRepository;
    private final CountryRepository countryRepository;
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${api.finnhub.key}")
    private String finnhubApiKey;

    @PostConstruct
    void seedOnFirstRun() {
        if (companyRepository.count() > 0) {
            log.info("Company graph already seeded, skipping startup ingestion.");
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                ingestCompaniesFromFinnhub();
            } catch (Exception e) {
                log.error("Startup company seeding failed: {}", e.getMessage(), e);
            }
        }).start();
    }

    @Transactional
    public Map<String, Object> ingestCompaniesFromFinnhub() {
        try {
            log.info("Starting Finnhub company ingestion...");
            int successCount = 0;
            int failureCount = 0;

            for (String category : TARGET_CATEGORIES) {
                List<Map<String, Object>> rawCompanies = fetchCompaniesForCategory(category);
                for (Map<String, Object> raw : rawCompanies) {
                    if (successCount >= MAX_COMPANIES) {
                        break;
                    }
                    String ticker = (String) raw.get("ticker");
                    try {
                        sleep(PROFILE_CALL_DELAY_MS);
                        JsonNode profile = fetchCompanyProfile(ticker);
                        ingestSingleCompany(raw, profile);
                        successCount++;
                    } catch (Exception e) {
                        failureCount++;
                        log.error("Failed to ingest company {}: {}", ticker, e.getMessage());
                    }
                }
            }

            log.info("Finnhub company ingestion complete. Ingested: {}, Failed: {}", successCount, failureCount);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("ingested", successCount);
            result.put("failed", failureCount);
            return result;
        } catch (Exception e) {
            log.error("Finnhub company ingestion failed unexpectedly", e);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "error");
            result.put("message", e.getMessage());
            result.put("stackTrace", abbreviatedStackTrace(e));
            return result;
        }
    }

    private List<String> abbreviatedStackTrace(Exception e) {
        StackTraceElement[] elements = e.getStackTrace();
        List<String> lines = new java.util.ArrayList<>();
        lines.add(e.toString());
        for (int i = 0; i < Math.min(STACK_TRACE_LINE_LIMIT, elements.length); i++) {
            lines.add("at " + elements[i]);
        }
        return lines;
    }

    private List<Map<String, Object>> fetchCompaniesForCategory(String category) {
        String url = FINNHUB_STOCK_LIST_URL + "?category=" + category + "&token=" + finnhubApiKey;
        try {
            log.info("Calling Finnhub stock/list for category {}: {}", category, url);
            ResponseEntity<String> httpResponse = restClient.get()
                    .uri(url)
                    .retrieve()
                    .onStatus(status -> status.value() == 429, (req, res) ->
                            log.warn("Finnhub rate limit hit for category {}, backing off.", category))
                    .toEntity(String.class);

            log.info("Received Finnhub stock/list response for category {} with status {}",
                    category, httpResponse.getStatusCode());

            if (httpResponse == null || httpResponse.getBody() == null || httpResponse.getBody().isEmpty()) {
                log.warn("No response from Finnhub for category {}", category);
                return Collections.emptyList();
            }

            if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                log.error("Finnhub returned {} for category {}", httpResponse.getStatusCode(), category);
                return Collections.emptyList();
            }

            String response = httpResponse.getBody();

            JsonNode root = objectMapper.readTree(response);
            if (!root.isArray()) {
                log.warn("Unexpected Finnhub response shape for category {}: not a JSON array", category);
                return Collections.emptyList();
            }

            List<Map<String, Object>> results = new java.util.ArrayList<>();
            for (JsonNode item : root) {
                String ticker = item.path("symbol").asText(null);
                String name = item.hasNonNull("displaySymbol")
                        ? item.path("displaySymbol").asText(null)
                        : item.path("description").asText(null);

                if (ticker == null || ticker.isBlank()) {
                    log.warn("Skipping Finnhub stock-list entry with missing ticker: {}", item);
                    continue;
                }
                if (name == null || name.isBlank()) {
                    log.warn("Missing displaySymbol/description for ticker {}, falling back to ticker as name", ticker);
                    name = ticker;
                }

                results.add(Map.of("ticker", ticker, "name", name));
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to fetch/parse Finnhub stock-list for category {} (url: {}): {}",
                    category, url, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private JsonNode fetchCompanyProfile(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return objectMapper.nullNode();
        }
        String url = FINNHUB_PROFILE_URL + "?symbol=" + ticker + "&token=" + finnhubApiKey;
        try {
            log.info("Calling Finnhub stock/profile2 for ticker {}: {}", ticker, url);
            ResponseEntity<String> httpResponse = restClient.get()
                    .uri(url)
                    .retrieve()
                    .onStatus(status -> status.value() == 429, (req, res) ->
                            log.warn("Finnhub rate limit hit fetching profile for {}, backing off.", ticker))
                    .toEntity(String.class);

            log.info("Received Finnhub stock/profile2 response for {} with status {}",
                    ticker, httpResponse.getStatusCode());

            if (httpResponse == null || httpResponse.getBody() == null || httpResponse.getBody().isEmpty()) {
                log.warn("No response from Finnhub for ticker {}", ticker);
                return objectMapper.nullNode();
            }

            if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                log.error("Finnhub returned {} for ticker {}", httpResponse.getStatusCode(), ticker);
                return objectMapper.nullNode();
            }

            String response = httpResponse.getBody();
            return objectMapper.readTree(response);
        } catch (Exception e) {
            log.error("Failed to fetch profile for {} (url: {}): {}", ticker, url, e.getMessage(), e);
            return objectMapper.nullNode();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Transactional
    void ingestSingleCompany(Map<String, Object> raw, JsonNode profile) {
        String ticker = (String) raw.get("ticker");
        String name = (String) raw.get("name");
        if (ticker == null || name == null) {
            throw new IllegalArgumentException("Missing ticker or name in Finnhub payload");
        }

        String finnhubSector = profile.path("finnhubIndustry").asText("");
        String countryCode = profile.path("country").asText(null);
        double marketCap = profile.path("marketCapitalization").asDouble(0);
        String description = profile.path("description").asText("");

        Company company = companyRepository.findByTicker(ticker).orElseGet(Company::new);
        company.setTicker(ticker);
        company.setName(name);
        company.setMarketCap(marketCap);
        company.setDescription(description);

        String domainSector = SECTOR_MAP.get(finnhubSector);
        if (domainSector != null) {
            company.setSector(getOrCreateSector(domainSector));
        }

        if (countryCode != null && !countryCode.isBlank()) {
            company.setHeadquarters(getOrCreateCountry(countryCode));
        }

        companyRepository.save(company);
        log.debug("Ingested company {} ({})", name, ticker);
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
}
