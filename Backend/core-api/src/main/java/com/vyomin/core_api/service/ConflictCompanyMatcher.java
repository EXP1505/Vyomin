package com.vyomin.core_api.service;

import com.vyomin.core_api.model.intelligencegraph.Company;
import com.vyomin.core_api.model.intelligencegraph.Conflict;
import com.vyomin.core_api.model.intelligencegraph.Country;
import com.vyomin.core_api.model.intelligencegraph.Investor;
import com.vyomin.core_api.model.intelligencegraph.Sector;
import com.vyomin.core_api.model.intelligencegraph.SupplyChain;
import com.vyomin.core_api.repository.intelligencegraph.CompanyRepository;
import com.vyomin.core_api.repository.intelligencegraph.CountryRepository;
import com.vyomin.core_api.repository.intelligencegraph.InvestorRepository;
import com.vyomin.core_api.repository.intelligencegraph.SectorRepository;
import com.vyomin.core_api.repository.intelligencegraph.SupplyChainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConflictCompanyMatcher {

    private static final int MAX_RESULTS = 10;
    private static final int KEYWORD_MATCH_WEIGHT = 10;
    private static final int GEOGRAPHIC_MATCH_WEIGHT = 5;
    private static final int SUPPLY_CHAIN_MATCH_WEIGHT = 3;

    private static final Map<String, String> KEYWORD_TO_SECTOR = new LinkedHashMap<>();
    static {
        for (String keyword : List.of("semiconductor", "chip", "tsmc", "nvda")) {
            KEYWORD_TO_SECTOR.put(keyword, "semiconductors");
        }
        for (String keyword : List.of("defense", "military", "aircraft", "f-16", "lockheed")) {
            KEYWORD_TO_SECTOR.put(keyword, "defense");
        }
        for (String keyword : List.of("oil", "petroleum", "energy")) {
            KEYWORD_TO_SECTOR.put(keyword, "oil");
        }
        for (String keyword : List.of("rare earth", "lithium", "cobalt")) {
            KEYWORD_TO_SECTOR.put(keyword, "rare-earths");
        }
    }

    private final CompanyRepository companyRepository;
    private final CountryRepository countryRepository;
    private final SectorRepository sectorRepository;
    private final SupplyChainRepository supplyChainRepository;
    private final InvestorRepository investorRepository;

    public Map<String, Object> findAffectedCompanies(Conflict conflict) {
        if (conflict == null) {
            log.warn("findAffectedCompanies called with null conflict");
            return Map.of("companies", List.of(), "error", "Conflict is null");
        }
        if (conflict.getId() == null) {
            log.warn("findAffectedCompanies called with conflict that has no ID");
            return Map.of("companies", List.of(), "error", "Conflict has no ID");
        }

        try {
            List<Map.Entry<ScoredCompany, Integer>> ranked = rankCompanies(conflict);
            log.info("Matched {} candidate companies for conflict {}", ranked.size(), conflict.getId());

            List<Company> topCompanies = ranked.stream()
                    .map(entry -> entry.getKey().company())
                    .limit(MAX_RESULTS)
                    .toList();

            Set<String> investorNames = collectInvestorNames(topCompanies);
            log.info("Found {} investors exposed via conflict {}", investorNames.size(), conflict.getId());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("companies", topCompanies);
            result.put("investors", investorNames);
            return result;
        } catch (Exception e) {
            log.error("Error matching companies for conflict {}: {}", conflict.getId(), e.getMessage(), e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("companies", Collections.emptyList());
            result.put("investors", Collections.emptyList());
            result.put("error", e.getMessage());
            return result;
        }
    }

    public Map<String, Object> analyzeConflict(Conflict conflict) {
        if (conflict == null) {
            log.warn("analyzeConflict called with null conflict");
            return Map.of("companies", List.of(), "investors", List.of(), "matchScores", Map.of(),
                    "error", "Conflict is null");
        }
        if (conflict.getId() == null) {
            log.warn("analyzeConflict called with conflict that has no ID");
            return Map.of("companies", List.of(), "investors", List.of(), "matchScores", Map.of(),
                    "error", "Conflict has no ID");
        }

        try {
            List<Map.Entry<ScoredCompany, Integer>> ranked = rankCompanies(conflict);
            log.info("Matched {} candidate companies for conflict {}", ranked.size(), conflict.getId());

            List<Company> topCompanies = ranked.stream()
                    .map(entry -> entry.getKey().company())
                    .limit(MAX_RESULTS)
                    .toList();

            Map<String, Integer> matchScores = new LinkedHashMap<>();
            ranked.stream().limit(MAX_RESULTS)
                    .forEach(entry -> matchScores.put(entry.getKey().company().getName(), entry.getValue()));

            Set<String> investorNames = collectInvestorNames(topCompanies);
            log.info("Found {} investors exposed via conflict {}", investorNames.size(), conflict.getId());

            Map<String, Object> result = new HashMap<>();
            result.put("companies", topCompanies);
            result.put("investors", investorNames);
            result.put("matchScores", matchScores);
            return result;
        } catch (Exception e) {
            log.error("Error analyzing conflict {}: {}", conflict.getId(), e.getMessage(), e);
            Map<String, Object> result = new HashMap<>();
            result.put("companies", Collections.emptyList());
            result.put("investors", Collections.emptyList());
            result.put("matchScores", Collections.emptyMap());
            result.put("error", e.getMessage());
            return result;
        }
    }

    private Set<String> collectInvestorNames(List<Company> companies) {
        Set<String> investorNames = new java.util.LinkedHashSet<>();
        if (companies == null) {
            return investorNames;
        }
        for (Company company : companies) {
            if (company != null && company.getInvestors() != null) {
                company.getInvestors().forEach(investor -> {
                    if (investor != null && investor.getName() != null) {
                        investorNames.add(investor.getName());
                    }
                });
            }
        }
        return investorNames;
    }

    /**
     * Company/Investor form a bidirectional Lombok @Data relationship, so using
     * Company itself as a HashMap key would recurse into equals/hashCode across
     * that cycle. Score by id instead and carry the entity alongside it.
     */
    private record ScoredCompany(Long id, Company company) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ScoredCompany other && id.equals(other.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    private List<Map.Entry<ScoredCompany, Integer>> rankCompanies(Conflict conflict) {
        Map<ScoredCompany, Integer> scores = new HashMap<>();

        List<Company> allCompanies = companyRepository.findAll();
        if (allCompanies == null) {
            allCompanies = Collections.emptyList();
        }

        Set<String> matchedSectors = matchSectorsByKeyword(conflict.getDescription());
        log.debug("Keyword-matched sectors for conflict {}: {}", conflict.getId(), matchedSectors);

        for (String sectorName : matchedSectors) {
            Sector sector = sectorRepository.findByName(sectorName).orElse(null);
            if (sector == null) {
                continue;
            }
            List<Company> companiesByKeyword = allCompanies.stream()
                    .filter(company -> company != null && company.getSector() != null
                            && sector.getId().equals(company.getSector().getId()))
                    .toList();
            if (companiesByKeyword == null) {
                companiesByKeyword = Collections.emptyList();
            }
            companiesByKeyword.forEach(company -> addScore(scores, company, KEYWORD_MATCH_WEIGHT));
        }
        log.debug("{} companies matched by keyword/sector for conflict {}", scores.size(), conflict.getId());

        Set<Country> conflictCountries = conflict.getInvolvedCountries();
        if (conflictCountries != null && !conflictCountries.isEmpty()) {
            allCompanies.stream()
                    .filter(company -> company != null && company.getHeadquarters() != null
                            && company.getHeadquarters().getName() != null
                            && conflictCountries.stream().anyMatch(country ->
                                    country != null && country.getName() != null
                                            && country.getName().equalsIgnoreCase(
                                                    company.getHeadquarters().getName())))
                    .forEach(company -> addScore(scores, company, GEOGRAPHIC_MATCH_WEIGHT));
        }
        log.debug("{} companies matched after geographic pass for conflict {}", scores.size(), conflict.getId());

        List<SupplyChain> allSupplyChains = supplyChainRepository.findAll();
        if (allSupplyChains == null) {
            allSupplyChains = Collections.emptyList();
        }
        for (ScoredCompany scored : List.copyOf(scores.keySet())) {
            Company company = scored.company();
            boolean threatened = allSupplyChains.stream()
                    .filter(route -> route != null
                            && (sameCompany(company, route.getSupplier()) || sameCompany(company, route.getRecipient())))
                    .anyMatch(route -> route.getTransitCountry() != null
                            && route.getTransitCountry().getName() != null
                            && conflictCountries != null
                            && conflictCountries.stream().anyMatch(country ->
                                    country != null && country.getName() != null
                                            && country.getName().equalsIgnoreCase(route.getTransitCountry().getName())));
            if (threatened) {
                addScore(scores, company, SUPPLY_CHAIN_MATCH_WEIGHT);
            }
        }
        log.debug("{} companies matched after supply-chain pass for conflict {}", scores.size(), conflict.getId());

        return scores.entrySet().stream()
                .sorted(Comparator.<Map.Entry<ScoredCompany, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .toList();
    }

    private boolean sameCompany(Company a, Company b) {
        return a != null && b != null && a.getId() != null && a.getId().equals(b.getId());
    }

    private Set<String> matchSectorsByKeyword(String description) {
        Set<String> sectors = new java.util.LinkedHashSet<>();
        if (description == null || description.isBlank()) {
            return sectors;
        }
        String lower = description.toLowerCase();
        KEYWORD_TO_SECTOR.forEach((keyword, sector) -> {
            if (lower.contains(keyword)) {
                sectors.add(sector);
            }
        });
        return sectors;
    }

    private void addScore(Map<ScoredCompany, Integer> scores, Company company, int weight) {
        scores.merge(new ScoredCompany(company.getId(), company), weight, Integer::sum);
    }
}
