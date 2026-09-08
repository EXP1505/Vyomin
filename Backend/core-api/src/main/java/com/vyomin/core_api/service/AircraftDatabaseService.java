package com.vyomin.core_api.service;

import com.vyomin.core_api.model.telemetry.AircraftDetails;
import com.vyomin.core_api.repository.AircraftDetailsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AircraftDatabaseService {

    // Insert in bounded batches via plain JDBC (not the JPA repository) so memory stays flat
    // regardless of file size - a saveAll() per batch would still pile every managed entity into
    // the persistence context for the life of the request, which defeats the point.
    private static final int SEED_BATCH_SIZE = 2000;

    private final AircraftDetailsRepository repository;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        if (repository.count() > 0) {
            log.info("Aircraft database already seeded ({} rows) - skipping CSV import.", repository.count());
            return;
        }

        log.info("Seeding aircraft_details from OpenSky Aircraft Database CSV...");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new ClassPathResource("aircraft-database-complete-2025-08.csv").getInputStream(), StandardCharsets.UTF_8))) {

            String line = br.readLine(); // skip header
            List<Object[]> batch = new ArrayList<>(SEED_BATCH_SIZE);
            long total = 0;

            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;

                List<String> tokens = parseCsvLine(line);
                if (tokens.size() > 26) {
                    String icao24 = tokens.get(0);
                    if (icao24 == null || icao24.isBlank()) continue;

                    batch.add(new Object[]{
                            icao24.toLowerCase().trim(),
                            tokens.get(26), // registration
                            tokens.get(13), // manufacturerName
                            tokens.get(14), // model
                            tokens.get(18), // operator
                    });
                    total++;
                }

                if (batch.size() >= SEED_BATCH_SIZE) {
                    insertBatch(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                insertBatch(batch);
            }
            log.info("Successfully seeded {} aircraft records.", total);
        } catch (IOException e) {
            log.error("Failed to load OpenSky Aircraft Database - aircraft enrichment will be unavailable", e);
        }
    }

    private void insertBatch(List<Object[]> batch) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO aircraft_details (icao24, registration, manufacturer_name, model, operator) " +
                        "VALUES (?, ?, ?, ?, ?) ON CONFLICT (icao24) DO NOTHING",
                batch);
    }

    public AircraftDetails getAircraftDetails(String icao24) {
        if (icao24 == null) return null;
        return repository.findById(icao24.toLowerCase().trim())
                .map(e -> new AircraftDetails(e.getIcao24(), e.getRegistration(), e.getManufacturerName(), e.getModel(), e.getOperator()))
                .orElse(null);
    }

    private List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder currentToken = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'') {
                // If it's a quote, toggle state. We don't append the quote itself.
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                // Comma outside quotes means end of token
                result.add(currentToken.toString().trim());
                currentToken.setLength(0); // reset
            } else {
                currentToken.append(c);
            }
        }
        result.add(currentToken.toString().trim());
        return result;
    }
}
