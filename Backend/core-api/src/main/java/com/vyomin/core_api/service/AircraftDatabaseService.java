package com.vyomin.core_api.service;

import com.vyomin.core_api.model.telemetry.AircraftDetails;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class AircraftDatabaseService {
    private final Map<String, AircraftDetails> database = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Loading OpenSky Aircraft Database...");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new ClassPathResource("aircraft-database-complete-2025-08.csv").getInputStream(), StandardCharsets.UTF_8))) {
            
            String line = br.readLine(); // skip header
            int count = 0;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                
                List<String> tokens = parseCsvLine(line);
                if (tokens.size() > 26) {
                    String icao24 = tokens.get(0);
                    if (icao24 == null || icao24.isBlank()) continue;
                    
                    String manufacturerName = tokens.get(13);
                    String model = tokens.get(14);
                    String operator = tokens.get(18);
                    String registration = tokens.get(26);

                    database.put(icao24.toLowerCase().trim(), new AircraftDetails(
                            icao24, registration, manufacturerName, model, operator
                    ));
                    count++;
                }
            }
            log.info("Successfully loaded {} aircraft records into memory.", count);
        } catch (Exception e) {
            log.error("Failed to load OpenSky Aircraft Database", e);
        }
    }

    public AircraftDetails getAircraftDetails(String icao24) {
        if (icao24 == null) return null;
        return database.get(icao24.toLowerCase().trim());
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
