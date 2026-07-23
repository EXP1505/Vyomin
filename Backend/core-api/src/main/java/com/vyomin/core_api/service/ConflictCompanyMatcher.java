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

    public List<Company> findAffectedCompanies(Conflict conflict) {
        return rankCompanies(conflict).stream()
                .map(entry -> entry.getKey().company())
                .limit(MAX_RESULTS)
                .toList();
    }

    public Map<String, Object> analyzeConflict(Conflict conflict) {
        List<Map.Entry<ScoredCompany, Integer>> ranked = rankCompanies(conflict);

        List<Company> topCompanies = ranked.stream()
                .map(entry -> entry.getKey().company())
                .limit(MAX_RESULTS)
                .toList();

        Map<String, Integer> matchScores = new LinkedHashMap<>();
        ranked.stream().limit(MAX_RESULTS)
                .forEach(entry -> matchScores.put(entry.getKey().company().getName(), entry.getValue()));

        Set<String> investorNames = new java.util.LinkedHashSet<>();
        for (Company company : topCompanies) {
            if (company.getInvestors() != null) {
                company.getInvestors().forEach(investor -> investorNames.add(investor.getName()));
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("companies", topCompanies);
        result.put("investors", investorNames);
        result.put("matchScores", matchScores);
        return result;
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

        Set<String> matchedSectors = matchSectorsByKeyword(conflict.getDescription());
        for (String sectorName : matchedSectors) {
            sectorRepository.findByName(sectorName).ifPresent(sector ->
                    companyRepository.findAll().stream()
                            .filter(company -> sector.getId().equals(
                                    company.getSector() == null ? null : company.getSector().getId()))
                            .forEach(company -> addScore(scores, company, KEYWORD_MATCH_WEIGHT)));
        }

        Set<Country> conflictCountries = conflict.getInvolvedCountries();
        if (conflictCountries != null && !conflictCountries.isEmpty()) {
            companyRepository.findAll().stream()
                    .filter(company -> company.getHeadquarters() != null
                            && conflictCountries.stream().anyMatch(country ->
                                    country.getName().equalsIgnoreCase(
                                            company.getHeadquarters().getName())))
                    .forEach(company -> addScore(scores, company, GEOGRAPHIC_MATCH_WEIGHT));
        }

        List<SupplyChain> allSupplyChains = supplyChainRepository.findAll();
        for (ScoredCompany scored : List.copyOf(scores.keySet())) {
            Company company = scored.company();
            boolean threatened = allSupplyChains.stream()
                    .filter(route -> sameCompany(company, route.getSupplier())
                            || sameCompany(company, route.getRecipient()))
                    .anyMatch(route -> route.getTransitCountry() != null
                            && conflictCountries != null
                            && conflictCountries.stream().anyMatch(country ->
                                    country.getName().equalsIgnoreCase(route.getTransitCountry().getName())));
            if (threatened) {
                addScore(scores, company, SUPPLY_CHAIN_MATCH_WEIGHT);
            }
        }

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
