package com.vyomin.core_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyomin.core_api.model.graph.Company;
import com.vyomin.core_api.model.graph.Country;
import com.vyomin.core_api.model.graph.Event;
import com.vyomin.core_api.repository.graph.CompanyRepository;
import com.vyomin.core_api.repository.graph.CountryRepository;
import com.vyomin.core_api.repository.graph.EventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
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
    private final EventRepository eventRepository;
    // for all the countries
    private final CountryRepository countryRepository;
    // for all the companies
    private final CompanyRepository companyRepository;
    //just making a rest client
    private final RestClient restClient = RestClient.create();
    //making an object mapper to parse the json response from the api's
    private final ObjectMapper objectMapper = new ObjectMapper();
    //getting the finnhub key from the properties
    @Value("${api.finnhub.key}")
    private String finnhubApiKey;
    //defining the countries we are targeting
    private static final List<String> TARGET_COUNTRIES = Arrays.asList("Russia", "China", "Iran", "USA", "North Korea","India","Israel","United Kingdom","Pakistan","Germany","France","Japan","South Korea","Brazil");
    //the schedule we are running the news ingestion for
    @Scheduled(fixedRate = 900000)
    public void ingestGdeltNews() {
        log.info("Starting GDELT news ingestion...");
        try {
            //getting the news from gdelt api about the countries we are targeting
            String url = "https://api.gdeltproject.org/api/v2/doc/doc?query=(military OR sanction)&mode=artlist&format=json&maxrecords=5";
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
        } catch (Exception e) {
            log.error("Failed to ingest GDELT data", e);
        }
    }

    public void ingestFinnhubCompany(String symbol) {
        log.info("Starting Finnhub ingestion for symbol: {}", symbol);
        try {
            //getting the company data from finnhub api
            String url = "https://finnhub.io/api/v1/stock/profile2?symbol=" + symbol + "&token=" + finnhubApiKey;
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
}
