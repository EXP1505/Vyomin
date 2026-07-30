package com.vyomin.core_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyomin.core_api.model.graph.Company;
import com.vyomin.core_api.model.graph.Country;
import com.vyomin.core_api.model.graph.Event;
import com.vyomin.core_api.repository.graph.GraphCompanyRepository;
import com.vyomin.core_api.repository.graph.GraphCountryRepository;
import com.vyomin.core_api.repository.graph.GraphEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IntelligenceIngestionService {
    //for all the events happening, its in repo folder
    private final GraphEventRepository eventRepository;
    // for all the countries
    private final GraphCountryRepository countryRepository;
    // for all the companies
    private final GraphCompanyRepository companyRepository;
    //just making a rest client
    private final RestClient restClient = RestClient.create();
    //making an object mapper to parse the json response from the api's
    private final ObjectMapper objectMapper = new ObjectMapper();
    //getting the finnhub key from the properties
    @Value("${api.finnhub.key}")
    private String finnhubApiKey;
    //getting the open network credentials from the properties
    @Value("${api.opennetwork.clientId}")
    private String openNetworkClientId;
    @Value("${api.opennetwork.clientSecret}")
    private String openNetworkClientSecret;

    //defining the countries we are targeting
    private static final List<String> TARGET_COUNTRIES = Arrays.asList("Russia", "China", "Iran", "USA", "North Korea","India","Israel","United Kingdom","Pakistan","Germany","France","Japan","South Korea","Brazil");
    
    //initialize real-time data ingestion on application startup
    //postconstruct used to run the method immediately after the service or the bean is created and the dependencies are injected
    @PostConstruct
    public void initializeDataIngestion() {
        log.info("Initializing Open_Network data ingestion on startup...");
        new Thread(() -> {
            try {
                Thread.sleep(2000); //wait 2 seconds for services to stabilize
                ingestOpenNetworkData();
            } catch (InterruptedException e) {
                log.error("Interrupted during initialization", e);
            }
        }).start();
    }
    
    //the schedule we are running the news ingestion for
    @Scheduled(fixedRate = 900000)
    public void ingestGdeltNews() {
        log.info("Starting GDELT news ingestion...");
        try {
            //getting the news from gdelt api about the countries we are targeting
            String url = System.getenv("gdelt_api_url");
            String response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
            //parsing the json response
            if (response != null && !response.isEmpty()) {
                JsonNode rootNode = objectMapper.readTree(response);
                JsonNode articles = rootNode.path("articles");
                //storing all the news if it contains the keywords 
                if (articles.isArray()) {
                    for (JsonNode article : articles) {
                        String title = article.path("title").asText();
                        log.info("Processing article: {}", title);
                        //creating an event object
                        Event event = new Event();
                        event.setHeadline(title);
                        event.setDate(LocalDate.now());
                        
                        for (String countryName : TARGET_COUNTRIES) {
                            if (title.contains(countryName)) {
                                Country country = getOrCreateCountry(countryName);
                                event.getImpactedCountries().add(country);
                            }
                        }
                        //storing the event and mapping the relationships
                        eventRepository.save(event);
                        log.info("Saved Event node and mapped relationships.");
                    }
                }
            }
        } catch (HttpClientErrorException e) {
            log.warn("GDELT ingestion failed (rate limited or offline): {}", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to ingest GDELT data", e);
        }
    }

    public void ingestFinnhubCompany(String symbol) {
        log.info("Starting Finnhub ingestion for symbol: {}", symbol);
        try {
            //getting the company data from finnhub api
            String url = System.getenv("finnhub_api_url") + "?symbol=" + symbol + "&token=" + finnhubApiKey;
            String response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
            //parsing the json response
            if (response != null && !response.isEmpty()) {
                JsonNode jsonNode = objectMapper.readTree(response);
                if (!jsonNode.isEmpty()) {
                    String name = jsonNode.path("name").asText();
                    String industry = jsonNode.path("finnhubIndustry").asText();
                    //finding the company by name or creating a new company
                    Company company = companyRepository.findByName(name).orElse(new Company());
                    company.setName(name);
                    company.setIndustry(industry);
                    //storing the company
                    companyRepository.save(company);
                    log.info("Saved Company node: {}", name);
                }
            }
        } catch (Exception e) {
            log.error("Failed to ingest Finnhub data", e);
        }
    }

    public void mapSanction(String sanctionerName, String targetName) {
        log.info("Mapping sanction from {} to {}", sanctionerName, targetName);
        //getting the country by name 
        Country sanctioner = getOrCreateCountry(sanctionerName);
        Country target = getOrCreateCountry(targetName);
        //adding the sanctioner to the target
        sanctioner.getSanctionedCountries().add(target);
        //storing the sanctioner
        countryRepository.save(sanctioner);
        log.info("Saved sanction relationship.");
    }
    //getting the country by name
    private Country getOrCreateCountry(String name) {
        return countryRepository.findByName(name).orElseGet(() -> {
            Country newCountry = new Country();
            newCountry.setName(name);
            newCountry.setRegion("Unknown");
            return countryRepository.save(newCountry);
        });
    }

    //scheduled to ingest real-time data from Open_Network API every 5 minutes
    @Scheduled(fixedRate = 300000)
    public void ingestOpenNetworkData() {
        log.info("Starting OpenSky Network real-time data ingestion...");
        try {
            //authentication endpoint to get token
            String authUrl = System.getenv("Open_Network_token_url");
            String authBody = "grant_type=client_credentials&client_id=" + openNetworkClientId + "&client_secret=" + openNetworkClientSecret;

            String rawAuthResponse = restClient.post()
                    .uri(authUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(authBody)
                    .retrieve()
                    .body(String.class);

            if (rawAuthResponse == null || rawAuthResponse.isBlank()) {
                log.error("Failed to authenticate with OpenSky Network API: empty response");
                return;
            }

            JsonNode authResponse = objectMapper.readTree(rawAuthResponse);
            if (!authResponse.has("access_token")) {
                log.error("Failed to authenticate with OpenSky Network API: {}", rawAuthResponse);
                return;
            }

            String accessToken = authResponse.path("access_token").asText();
            log.info("Successfully obtained access token from OpenSky Network");

            
            //fetch real flight data using the access token and the url
            String dataUrl = System.getenv("Open_Network_data_url");
            String response = restClient.get()
                    .uri(dataUrl)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);

            if (response != null && !response.isEmpty()) {
                JsonNode rootNode = objectMapper.readTree(response);
                JsonNode states = rootNode.path("states");
                //checking if the states array is not empty and processing the flight data
                if (states.isArray() && states.size() > 0) {
                    log.info("Fetched {} aircraft states from OpenSky Network", states.size());
                    
                    for (JsonNode state : states) {
                        try {
                            String callsign = state.path(1).asText("UNKNOWN").trim();
                            String countryName = state.path(2).asText("");
                            double latitude = state.path(6).asDouble();
                            double longitude = state.path(5).asDouble();
                            double altitude = state.path(7).asDouble();
                            
                            if (callsign.isEmpty() || countryName.isEmpty()) continue;
                            
                            //create or update country node
                            Country country = getOrCreateCountry(countryName);
                            log.debug("Processing aircraft {} from {}", callsign, countryName);
                        } catch (Exception e) {
                            log.debug("Error processing aircraft state", e);
                        }
                    }
                    
                    log.info("Successfully processed {} aircraft states", states.size());
                } else {
                    log.info("No aircraft states available at this time");
                }
            }
        } catch (Exception e) {
            log.error("Failed to ingest OpenSky Network data", e);
        }
    }
}
