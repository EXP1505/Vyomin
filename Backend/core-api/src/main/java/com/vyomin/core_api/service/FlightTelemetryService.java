package com.vyomin.core_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyomin.core_api.model.telemetry.AircraftDetails;
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
import java.util.Set;

@Service
@Slf4j
public class FlightTelemetryService {
    //redis template to store the flights data
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    // simp messaging template to broadcast the flights data
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    // Database service for aircraft models
    @Autowired
    private AircraftDatabaseService aircraftDatabaseService;
    
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

    // ── Known military callsign prefixes (ICAO / NATO / common) ──────────────
    private static final Set<String> MILITARY_PREFIXES = Set.of(
        "RCH",  // USAF Air Mobility Command (Reach)
        "SAM",  // Special Air Mission (Air Force One family)
        "PAT",  // US Army
        "DUKE", // USAF fighter callsigns
        "TOPGUN",
        "VIPER", "FALCON", "RAPTOR", "EAGLE", "VENOM",
        "COBRA", "GHOST", "REAPER", "PREDATOR",
        "KNIFE", "SWORD", "LANCE", "SPEAR",
        "NATO",  // NATO AWACS / flights
        "AWACS",
        "JAKE",  // USAF
        "HOMER", "ROCKY", "BISON",
        "UAL",   // NOTE: not military — but kept separate
        "MAGMA", // RAF special ops
        "ASCOT", // RAF Air Mobility
        "ATLAS", // RAF A400M
        "VORTEX",// USAF tanker
        "QUID",  // RAF
        "STING",
        "CTM",   // French military
        "FAF",   // French Air Force
        "GAF",   // German Air Force
        "IAM",   // Indian Air Force
        "CFC",   // Canadian Forces
        "CZAF",  // Czech Air Force
        "PLF",   // Polish Air Force
        "RFF",   // Russian Federal Air Forces
        "HERKY", // C-130 Hercules
        "SPAR"   // USAF VIP transport
    );

    // ── Known cargo airline prefixes ─────────────────────────────────────────
    private static final Set<String> CARGO_PREFIXES = Set.of(
        "FDX",  // FedEx
        "UPS",  // UPS
        "GTI",  // Atlas Air / Giant
        "ABX",  // ABX Air
        "KMI",  // Kalitta Air
        "NCB",  // National Air Cargo
        "CLX",  // Cargolux
        "BOX",  // ASL Airlines (freight)
        "TAY",  // Jet2 Cargo
        "MPH",  // Morningstar Air Express
        "SWN",  // Sun Country Cargo
        "TGO",  // TNT Airways
        "VGG"   // DHL
    );

    // ── Known helicopter operators ────────────────────────────────────────────
    private static final Set<String> HELICOPTER_PREFIXES = Set.of(
        "HXA", "HXB", "HXC",  // Air Methods
        "ERA",                  // Era Helicopters
        "PHT",                  // PHI Air Medical
        "MED", "MEDEVAC",       // Medical helicopters
        "LIFE", "LIFEFLIGHT",
        "STAR",                 // HEMS
        "ORNGE"                 // Ontario Air Ambulance
    );

    // ── Known private / bizjet prefixes ─────────────────────────────────────
    private static final Set<String> PRIVATE_PREFIXES = Set.of(
        "N", "VH", "G-", "D-", "F-",  // general-aviation registration prefixes (handled separately)
        "EJA",  // NetJets
        "NJE",  // NetJets Europe
        "TWY",  // Textron
        "WSG",  // VistaJet
        "VJT"   // VistaJet
    );
    //scheduled to fetch and broadcast real flight data every 5 minutes
    @Scheduled(fixedRate = 300000)
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
                        // longitude, latitude, baro_altitude, on_ground, velocity, true_track,
                        // vertical_rate, sensors, geo_altitude, squawk, spi, position_source, category]
                        String icao24      = state.path(0).asText("").trim();
                        String callsign    = state.path(1).asText("UNKNOWN").trim();
                        double latitude    = state.path(6).asDouble();
                        double longitude   = state.path(5).asDouble();
                        double altitude    = state.path(7).asDouble(0);
                        int    heading     = state.path(10).asInt(0);
                        String squawk      = state.path(14).asText("");
                        int    category    = state.path(17).asInt(0);

                        if (!callsign.isEmpty() && latitude != 0 && longitude != 0) {
                            String flightType = classifyFlight(callsign, squawk, category);
                            String model = null;
                            String reg = null;

                            if (!icao24.isEmpty()) {
                                AircraftDetails details = aircraftDatabaseService.getAircraftDetails(icao24);
                                if (details != null) {
                                    model = details.getManufacturerName() + " " + details.getModel();
                                    if (model.isBlank()) model = null;
                                    reg = details.getRegistration();
                                    if (reg != null && reg.isBlank()) reg = null;
                                }
                            }

                            flights.add(new FlightTelemetry(
                                    callsign, latitude, longitude,
                                    (int) altitude, heading,
                                    System.currentTimeMillis(),
                                    flightType, model, reg
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
        flights.add(new FlightTelemetry("GHOST01",  34.1, -118.2, 35000, 180, System.currentTimeMillis(), "MILITARY", "Lockheed AC-130J", "USAF-123"));
        flights.add(new FlightTelemetry("VIPER22",  36.1, -115.1, 28000, 270, System.currentTimeMillis(), "MILITARY", "General Dynamics F-16C", "USAF-456"));
        flights.add(new FlightTelemetry("REAPER9",  33.5, -112.0, 45000,  90, System.currentTimeMillis(), "MILITARY", "General Atomics MQ-9", null));
        return flights;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flight classification
    // ─────────────────────────────────────────────────────────────────────────
    private String classifyFlight(String callsign, String squawk, int category) {
        if (callsign == null || callsign.isBlank()) return "UNKNOWN";
        String cs = callsign.toUpperCase().trim();

        // ADS-B category 9 = rotorcraft/helicopter
        if (category == 9) return "HELICOPTER";
        // ADS-B category 15 = UAV/drone
        if (category == 15) return "DRONE";
        // ADS-B categories 10-14, 16+ = special (glider, ultralight, lighter-than-air, etc.)
        if (category >= 10 && category <= 14) return "PRIVATE";

        // Squawk 7777 is the internationally reserved military squawk
        if ("7777".equals(squawk)) return "MILITARY";

        // Check military prefixes (longest match wins)
        for (String prefix : MILITARY_PREFIXES) {
            if (cs.startsWith(prefix)) return "MILITARY";
        }

        // Check cargo airline prefixes
        for (String prefix : CARGO_PREFIXES) {
            if (cs.startsWith(prefix)) return "CARGO";
        }

        // Check helicopter operators
        for (String prefix : HELICOPTER_PREFIXES) {
            if (cs.startsWith(prefix)) return "HELICOPTER";
        }

        // Check business aviation / private prefixes
        for (String prefix : PRIVATE_PREFIXES) {
            if (cs.startsWith(prefix)) return "PRIVATE";
        }

        // General-aviation heuristic: short callsigns (≤6 chars, all alphanum, no airline IATA pattern)
        // Commercial IATA callsigns are typically 2-letter code + 1-4 digit flight number
        if (cs.length() >= 2 && cs.length() <= 3 && cs.chars().allMatch(Character::isLetter)) {
            // Likely 2-3 letter IATA code prefix → commercial
            return "COMMERCIAL";
        }
        // If the first 2-3 chars are letters and the rest are digits → commercial airline flight
        if (cs.matches("[A-Z]{2,3}[0-9]{1,4}[A-Z]?")) return "COMMERCIAL";

        // Purely numeric or very short → general aviation / private
        if (cs.matches("[0-9A-Z]{1,5}")) return "PRIVATE";

        return "UNKNOWN";
    }
}
