package com.vyomin.core_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyomin.core_api.model.telemetry.FlightTelemetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class FlightTelemetryService {
    //redis template to store the flights data
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    // simp messaging template to broadcast the flights data
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    //redis key to store the flights data
    private final String REDIS_KEY = "FLIGHTS_LATEST";
    //rest client for API calls
    private final RestClient restClient = RestClient.create();
    //object mapper for JSON parsing
    private final ObjectMapper objectMapper = new ObjectMapper();
    //OpenSky Network credentials
    @Value("${api.opennetwork.clientId}")
    private String clientId;
    @Value("${api.opennetwork.clientSecret}")
    private String clientSecret;
    //OpenSky Network URLs from env
    @Value("${api.opennetwork.tokenUrl}")
    private String tokenUrl;
    @Value("${api.opennetwork.dataUrl}")
    private String dataUrl;
    //cache for access token
    private String cachedAccessToken;
    private long tokenExpirationTime = 0;
    
    //scheduled to fetch and broadcast real flight data every 10 seconds
    @Scheduled(fixedRate = 10000)
    public void fetchAndBroadcastFlights() {
        try {
            List<FlightTelemetry> flights = fetchRealFlights();
            
            // Save latest snapshot to Redis
            redisTemplate.opsForValue().set(REDIS_KEY, flights);

            // Broadcast to WebSocket topic
            messagingTemplate.convertAndSend("/topic/flights", flights);
            
            log.debug("Broadcasted {} flights to clients", flights.size());
        } catch (Exception e) {
            log.error("Error fetching and broadcasting flights", e);
        }
    }

    private List<FlightTelemetry> fetchRealFlights() throws Exception {
        List<FlightTelemetry> flights = new ArrayList<>();

        // ensure we have a valid token — throws if auth fails
        if (System.currentTimeMillis() > tokenExpirationTime) {
            refreshAccessToken();
        }

        // fetch real flight data
        log.debug("Fetching flights from {} with token [{}...]", dataUrl,
                cachedAccessToken != null ? cachedAccessToken.substring(0, Math.min(10, cachedAccessToken.length())) : "null");
        String response = restClient.get()
                .uri(dataUrl)
                .header("Authorization", "Bearer " + cachedAccessToken)
                .retrieve()
                .body(String.class);

        if (response != null && !response.isEmpty()) {
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode states = rootNode.path("states");

            if (states.isArray()) {
                int count = 0;
                for (JsonNode state : states) {
                    try {
                        // array format: [icao24, callsign, origin_country, time_position, last_contact,
                        // longitude, latitude, baro_altitude, on_ground, velocity, true_track]
                        String callsign = state.path(1).asText("UNKNOWN").trim();
                        double latitude = state.path(6).asDouble();
                        double longitude = state.path(5).asDouble();
                        double altitude = state.path(7).asDouble(0);
                        int heading = state.path(10).asInt(0);

                        if (!callsign.isEmpty() && latitude != 0 && longitude != 0) {
                            flights.add(new FlightTelemetry(
                                    callsign, latitude, longitude,
                                    (int) altitude, heading,
                                    System.currentTimeMillis()
                            ));
                            count++;
                        }
                    } catch (Exception e) {
                        log.debug("Error parsing flight state", e);
                    }
                }
                log.info("Fetched {} real flights from OpenSky Network", count);
            } else {
                log.warn("OpenSky response contained no 'states' array. Raw response: {}",
                        response.substring(0, Math.min(300, response.length())));
            }
        }

        return flights;
    }

    private void refreshAccessToken() throws Exception {
        log.info("Requesting OpenSky Network access token from {} using clientId={}", tokenUrl, clientId);
        String authBody = "grant_type=client_credentials&client_id=" + clientId + "&client_secret=" + clientSecret;

        String rawResponse;
        try {
            rawResponse = restClient.post()
                    .uri(tokenUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(authBody)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("HTTP error while requesting OpenSky token from {}: {}", tokenUrl, e.getMessage());
            throw new RuntimeException("OpenSky token request failed: " + e.getMessage(), e);
        }

        if (rawResponse == null || rawResponse.isBlank()) {
            log.error("OpenSky auth endpoint returned empty response");
            throw new RuntimeException("OpenSky authentication failed — empty response from token endpoint");
        }

        JsonNode authResponse = objectMapper.readTree(rawResponse);

        if (!authResponse.has("access_token")) {
            log.error("OpenSky auth response missing access_token. Full response: {}", rawResponse);
            throw new RuntimeException("OpenSky authentication failed — no access_token in response: " + rawResponse);
        }

        cachedAccessToken = authResponse.path("access_token").asText();
        int expiresIn = authResponse.path("expires_in").asInt(3600);
        tokenExpirationTime = System.currentTimeMillis() + (expiresIn * 1000L) - 60000; // refresh 1 min before expiry
        log.info("Successfully obtained OpenSky Network access token (expires in {} seconds)", expiresIn);
    }

    private List<FlightTelemetry> generateMockFlights() {
        List<FlightTelemetry> flights = new ArrayList<>();
        
        flights.add(new FlightTelemetry("GHOST01", 34.1, -118.2, 35000, 180, System.currentTimeMillis()));
        flights.add(new FlightTelemetry("VIPER22", 36.1, -115.1, 28000, 270, System.currentTimeMillis()));
        flights.add(new FlightTelemetry("REAPER9", 33.5, -112.0, 45000, 90, System.currentTimeMillis()));

        return flights;
    }
}
