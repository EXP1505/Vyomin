package com.vyomin.core_api.service;

import com.vyomin.core_api.model.intelligencegraph.Conflict;
import com.vyomin.core_api.model.intelligencegraph.Country;
import com.vyomin.core_api.repository.intelligencegraph.ConflictRepository;
import com.vyomin.core_api.repository.intelligencegraph.CountryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.io.StringReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class GdeltIngestionService {

    private static final String GDELT_DAILY_UPDATES_URL = "http://gdeltproject.org/data/dailyupdates/";
    private static final DateTimeFormatter GDELT_DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final double MIN_SEVERITY = 5.0;

    private final ConflictRepository conflictRepository;
    private final CountryRepository countryRepository;
    private final RestClient restClient = RestClient.create();

    @Transactional
    public void fetchAndIngestDailyGdelt() {
        log.info("Starting GDELT daily ingestion...");
        String csvUrl = resolveLatestCsvUrl();
        if (csvUrl == null) {
            log.error("Could not resolve a GDELT daily CSV URL, aborting ingestion.");
            return;
        }

        String csvBody = downloadCsv(csvUrl);
        if (csvBody == null || csvBody.isBlank()) {
            log.error("Downloaded GDELT CSV was empty, aborting ingestion.");
            return;
        }

        int ingested = 0;
        int skipped = 0;
        int errors = 0;

        try (CSVParser parser = CSVFormat.TDF.parse(new StringReader(csvBody))) {
            for (CSVRecord record : parser) {
                try {
                    if (ingestRecord(record)) {
                        ingested++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    errors++;
                    log.warn("Skipping malformed GDELT row {}: {}", record.getRecordNumber(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse GDELT CSV: {}", e.getMessage(), e);
            return;
        }

        log.info("GDELT ingestion complete. Ingested: {}, Skipped (dedup/low severity): {}, Errors: {}",
                ingested, skipped, errors);
    }

    private String resolveLatestCsvUrl() {
        String today = LocalDate.now().format(GDELT_DAY_FORMAT);
        return GDELT_DAILY_UPDATES_URL + today + ".export.CSV";
    }

    private String downloadCsv(String url) {
        try {
            return restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("Failed to download GDELT CSV from {}: {}", url, e.getMessage());
            return null;
        }
    }

    private boolean ingestRecord(CSVRecord record) {
        if (record.size() < 25) {
            throw new IllegalArgumentException("Row has fewer fields than expected: " + record.size());
        }

        String globalEventId = record.get(0);
        if (conflictRepository.findByGdeltEventId(globalEventId).isPresent()) {
            return false;
        }

        String day = record.get(1);
        String actor1Code = record.get(5);
        String actor2Code = record.get(6);
        String eventCode = record.get(7);
        double goldsteinScale = parseDoubleSafe(record.get(11));
        String actionGeoFullname = record.get(23);
        String actionGeoCountryCode = record.get(24);

        int severityScore = (int) Math.round((goldsteinScale + 10) / 2.0);
        if (severityScore < MIN_SEVERITY) {
            return false;
        }

        Conflict conflict = new Conflict();
        conflict.setGdeltEventId(globalEventId);
        conflict.setStartDate(parseGdeltDay(day));
        conflict.setEventType(mapEventType(eventCode));
        conflict.setDescription(buildDescription(actor1Code, actor2Code, eventCode));
        conflict.setPrimaryRegion(actionGeoFullname);
        conflict.setSeverityScore(severityScore);
        conflict.setSeverity(String.valueOf(severityScore));
        conflict.setName(conflict.getEventType() + " - " + actionGeoFullname);

        if (actionGeoCountryCode != null && !actionGeoCountryCode.isBlank()) {
            conflict.getInvolvedCountries().add(getOrCreateCountry(actionGeoCountryCode));
        }

        conflictRepository.save(conflict);
        return true;
    }

    private String mapEventType(String eventCode) {
        if (eventCode == null || eventCode.isBlank()) {
            return "unknown";
        }
        if (eventCode.startsWith("18") || eventCode.startsWith("19") || eventCode.startsWith("20")) {
            return "war";
        }
        if (eventCode.startsWith("01") || eventCode.startsWith("02")) {
            return "statement";
        }
        if (eventCode.contains("sanction") || eventCode.contains("embargo")) {
            return eventCode.contains("embargo") ? "embargo" : "sanction";
        }
        return "other";
    }

    private String buildDescription(String actor1Code, String actor2Code, String eventCode) {
        String actor1 = actor1Code == null || actor1Code.isBlank() ? "Unknown Actor" : actor1Code;
        String actor2 = actor2Code == null || actor2Code.isBlank() ? "Unknown Actor" : actor2Code;
        return actor1 + " - " + mapEventType(eventCode) + " - " + actor2;
    }

    private Country getOrCreateCountry(String countryCode) {
        return countryRepository.findByName(countryCode).orElseGet(() -> {
            Country country = new Country();
            country.setName(countryCode);
            country.setRegion("Unknown");
            return countryRepository.save(country);
        });
    }

    private double parseDoubleSafe(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private LocalDate parseGdeltDay(String day) {
        try {
            return LocalDate.parse(day, GDELT_DAY_FORMAT);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }
}
