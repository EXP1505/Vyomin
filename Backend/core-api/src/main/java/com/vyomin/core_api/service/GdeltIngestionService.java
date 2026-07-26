package com.vyomin.core_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyomin.core_api.model.intelligencegraph.Conflict;
import com.vyomin.core_api.model.intelligencegraph.Country;
import com.vyomin.core_api.repository.intelligencegraph.ConflictRepository;
import com.vyomin.core_api.repository.intelligencegraph.CountryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GdeltIngestionService {

    private static final String GDELT_DOC_API_URL =
            "https://api.gdeltproject.org/api/v2/doc/doc?query=(military OR sanction OR war OR embargo)" +
                    "&mode=artlist&format=json&maxrecords=100";
    private static final DateTimeFormatter GDELT_SEENDATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final Map<String, EventClassification> KEYWORD_CLASSIFICATION = new LinkedHashMap<>();
    static {
        KEYWORD_CLASSIFICATION.put("war", new EventClassification("war", 9));
        KEYWORD_CLASSIFICATION.put("embargo", new EventClassification("embargo", 7));
        KEYWORD_CLASSIFICATION.put("sanction", new EventClassification("sanction", 6));
        KEYWORD_CLASSIFICATION.put("military", new EventClassification("military", 6));
    }
    private static final EventClassification DEFAULT_CLASSIFICATION = new EventClassification("other", 5);

    private record EventClassification(String eventType, int severityScore) {
    }

    private final ConflictRepository conflictRepository;
    private final CountryRepository countryRepository;
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public Map<String, Object> fetchAndIngestDailyGdelt() {
        log.info("Calling GDELT v2 doc API: {}", GDELT_DOC_API_URL);

        ResponseEntity<String> response;
        try {
            response = restClient.get()
                    .uri(GDELT_DOC_API_URL)
                    .retrieve()
                    .toEntity(String.class);
        } catch (Exception e) {
            log.error("Failed to call GDELT API {}: {}", GDELT_DOC_API_URL, e.getMessage(), e);
            return buildResult("error", 0, 0);
        }

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("GDELT API returned {}: {}", response.getStatusCode(), response.getBody());
            return buildResult("error", 0, 0);
        }

        String body = response.getBody();
        if (body == null || body.isBlank()) {
            log.warn("GDELT API response was empty");
            return buildResult("error", 0, 0);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.error("Failed to parse GDELT JSON response: {}", e.getMessage(), e);
            return buildResult("error", 0, 0);
        }

        JsonNode articles = root.path("articles");
        if (!articles.isArray()) {
            log.warn("GDELT response contained no 'articles' array");
            return buildResult("success", 0, 0);
        }

        int added = 0;
        int failed = 0;

        for (JsonNode article : articles) {
            try {
                if (ingestArticle(article) != null) {
                    added++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("Skipping malformed GDELT article {}: {}", article, e.getMessage());
            }
        }

        log.info("GDELT ingestion complete. Added: {}, Failed: {}, Total articles: {}",
                added, failed, articles.size());

        return buildResult("success", added, failed);
    }

    private Map<String, Object> buildResult(String status, int added, int failed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("conflictsAdded", added);
        result.put("failed", failed);
        return result;
    }

    private Conflict ingestArticle(JsonNode article) {
        if (article == null) {
            log.warn("Skipping null GDELT article");
            return null;
        }

        String url = article.hasNonNull("url") ? article.get("url").asText(null) : null;
        if (url == null || url.isBlank()) {
            log.warn("Skipping GDELT article with missing url: {}", article);
            return null;
        }

        try {
            if (conflictRepository.findByGdeltEventId(url).isPresent()) {
                return null;
            }

            String title = article.hasNonNull("title") ? article.get("title").asText("Unknown") : "Unknown";
            String sourceCountry = article.hasNonNull("sourcecountry") ? article.get("sourcecountry").asText(null) : null;

            LocalDate eventDate;
            try {
                if (!article.hasNonNull("seendate")) {
                    throw new IllegalArgumentException("missing seendate field");
                }
                String dateStr = article.get("seendate").asText().substring(0, 8);
                eventDate = LocalDate.parse(dateStr, GDELT_SEENDATE_FORMAT);
            } catch (Exception e) {
                log.warn("Failed to parse date {} for article {}: {}",
                        article.path("seendate").asText(null), url, e.getMessage());
                eventDate = LocalDate.now();
            }

            EventClassification classification;
            try {
                classification = classify(title);
            } catch (Exception e) {
                log.warn("Failed to classify article {}: {}", url, e.getMessage());
                classification = DEFAULT_CLASSIFICATION;
            }

            Conflict conflict = new Conflict();
            conflict.setGdeltEventId(url);
            conflict.setStartDate(eventDate);
            conflict.setEventType(classification.eventType());
            conflict.setSeverityScore(classification.severityScore());
            conflict.setSeverity(String.valueOf(classification.severityScore()));
            conflict.setDescription(title);
            conflict.setPrimaryRegion(sourceCountry);
            conflict.setName(classification.eventType() + " - " + title);

            if (sourceCountry != null && !sourceCountry.isBlank()) {
                conflict.getInvolvedCountries().add(getOrCreateCountry(sourceCountry));
            }

            conflictRepository.save(conflict);
            return conflict;
        } catch (Exception e) {
            log.warn("Failed to ingest GDELT article {}: {}", url, e.getMessage(), e);
            return null;
        }
    }

    private EventClassification classify(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_CLASSIFICATION;
        }
        String lower = title.toLowerCase();
        for (Map.Entry<String, EventClassification> entry : KEYWORD_CLASSIFICATION.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return DEFAULT_CLASSIFICATION;
    }

    private Country getOrCreateCountry(String countryCode) {
        return countryRepository.findByName(countryCode).orElseGet(() -> {
            Country country = new Country();
            country.setName(countryCode);
            country.setRegion("Unknown");
            return countryRepository.save(country);
        });
    }
}
