package com.vyomin.core_api.controller;

import com.vyomin.core_api.model.intelligencegraph.Company;
import com.vyomin.core_api.model.intelligencegraph.Conflict;
import com.vyomin.core_api.model.intelligencegraph.Country;
import com.vyomin.core_api.model.intelligencegraph.Investor;
import com.vyomin.core_api.model.intelligencegraph.Sector;
import com.vyomin.core_api.model.intelligencegraph.SupplyChain;
import com.vyomin.core_api.repository.graph.GraphCompanyRepository;
import com.vyomin.core_api.repository.intelligencegraph.ConflictRepository;
import com.vyomin.core_api.repository.intelligencegraph.CountryRepository;
import com.vyomin.core_api.repository.intelligencegraph.InvestorRepository;
import com.vyomin.core_api.repository.intelligencegraph.SectorRepository;
import com.vyomin.core_api.repository.intelligencegraph.SupplyChainRepository;
import com.vyomin.core_api.service.AsyncDataFetchService;
import com.vyomin.core_api.service.CompanyIngestionService;
import com.vyomin.core_api.service.ConflictCompanyMatcher;
import com.vyomin.core_api.service.GdeltIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/intel")
@Slf4j
public class IntelligenceController {
    // Autowiring companyrepo from repository to find out the risk paths from a person to a sanctioned country

    @Autowired
    private GraphCompanyRepository graphCompanyRepository;

    @Autowired
    private com.vyomin.core_api.repository.intelligencegraph.CompanyRepository intelligenceCompanyRepository;

    @Autowired
    private ConflictRepository conflictRepository;

    @Autowired
    private SupplyChainRepository supplyChainRepository;

    @Autowired
    private CompanyIngestionService companyIngestionService;

    @Autowired
    private GdeltIngestionService gdeltIngestionService;

    @Autowired
    private ConflictCompanyMatcher conflictCompanyMatcher;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private SectorRepository sectorRepository;

    @Autowired
    private InvestorRepository investorRepository;

    @Autowired
    private AsyncDataFetchService asyncDataFetchService;

    private static final Set<String> VALID_SEARCH_TYPES = Set.of(
            "company", "sector", "country", "conflict", "investor", "supply");
    private static final int SEARCH_RESULT_LIMIT = 50;

    // Without this, each repository call below opened its own Neo4j session/connection instead
    // of sharing one - 6-8 separate connection acquisitions per request, which was enough on its
    // own (even with no background ingestion running concurrently) to exhaust AuraDB's connection
    // pool and time out waiting for a free connection.
    @Transactional(readOnly = true)
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("q") String q,
                                     @RequestParam("type") String type) {
      try {
        log.info("search() body started: q='{}', type='{}'", q, type);
        log.info("intelligenceCompanyRepository: {}", intelligenceCompanyRepository != null ? "injected" : "NULL");
        log.info("conflictRepository: {}", conflictRepository != null ? "injected" : "NULL");
        log.info("countryRepository: {}", countryRepository != null ? "injected" : "NULL");
        log.info("sectorRepository: {}", sectorRepository != null ? "injected" : "NULL");
        log.info("q.isBlank()={}, type='{}'", q == null ? "N/A (q is null)" : q.isBlank(), type);

        if (type == null || !VALID_SEARCH_TYPES.contains(type.toLowerCase())) {
            log.warn("Returning response: query={}, type={} invalid", q, type);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid type. Must be one of: company, sector, country, conflict, investor, supply"));
        }
        if (q == null || q.isBlank()) {
            log.warn("Returning response: query={} blank/missing, type={}", q, type);
            return ResponseEntity.badRequest().body(Map.of("error", "Query parameter 'q' is required"));
        }

        String searchType = type.toLowerCase();
        String query = q.trim();
        String requestId = UUID.randomUUID().toString();

        log.info("Search started: query={}, type={}, requestId={}", query, searchType, requestId);

        try {
            List<Company> companies = List.of();
            List<Conflict> conflicts = List.of();
            List<Investor> investors = List.of();
            List<Sector> sectors = List.of();
            List<Country> countries = List.of();
            Map<String, Integer> matchScores = Map.of();

            log.info("About to enter switch with type={}", searchType);

            switch (searchType) {
                case "company" -> {
                    log.info("Entered company case");
                    log.info("Entered company case block");
                    log.info("Processing company search for: {}", query);

                    try {
                        companies = findCompaniesByTickerOrName(query);
                        log.info("companiesFound size: {}", companies.size());

                        if (companies.isEmpty()) {
                            log.info("Returning response: query={}, type={}, totalMatches=0 (no companies found)",
                                    query, searchType);
                            return ResponseEntity.ok(emptySearchResponse(query, searchType, requestId));
                        }

                        Map<String, Object> matched = matchConflictsForCompanies(companies);
                        log.info("matched result: {}", matched != null ? "not null" : "NULL");

                        conflicts = castConflicts(matched);
                        log.info("conflicts cast: {}", conflicts.size());

                        matchScores = castMatchScores(matched);
                        log.info("matchScores cast: {}", matchScores != null ? "not null" : "NULL");

                        investors = collectInvestors(companies);
                        log.info("investors collected: {}", investors.size());

                        sectors = collectSectors(companies);
                        log.info("sectors collected: {}", sectors.size());
                    } catch (Exception e) {
                        log.error("Exception in company case", e);
                        return ResponseEntity.ok(emptySearchResponse(query, searchType, requestId));
                    }

                    log.info("Company search complete: companies={}, conflicts={}, investors={}",
                            companies.size(), conflicts.size(), investors.size());
                }
                case "sector" -> {
                    log.info("About to process sector search");
                    log.info("Entered sector case block");
                    log.info("Processing sector search for: {}", query);

                    List<Sector> sectorsFound;
                    try {
                        sectorsFound = findSectorsByName(query);
                        log.info("findSectorsByName returned: {}",
                                sectorsFound != null ? "List of " + sectorsFound.size() : "NULL");
                    } catch (Exception e) {
                        log.error("Exception in findSectorsByName", e);
                        return ResponseEntity.ok(emptySearchResponse(query, searchType, requestId));
                    }
                    log.info("sectorsFound size: {}", sectorsFound.size());

                    if (sectorsFound.isEmpty()) {
                        log.info("Returning response: query={}, type={}, totalMatches=0 (no sectors found)",
                                query, searchType);
                        return ResponseEntity.ok(emptySearchResponse(query, searchType, requestId));
                    }

                    Set<Long> sectorIds = sectorsFound.stream().map(Sector::getId)
                            .filter(Objects::nonNull).collect(Collectors.toSet());
                    log.info("sectorIds: {}", sectorIds);

                    companies = intelligenceCompanyRepository.findAll().stream()
                            .filter(c -> c != null && c.getSector() != null
                                    && sectorIds.contains(c.getSector().getId()))
                            .toList();
                    log.info("companies found: {}", companies.size());

                    Map<String, Object> matched = matchConflictsForCompanies(companies);
                    log.info("matched result: {}", matched != null ? "not null" : "NULL");

                    conflicts = castConflicts(matched);
                    log.info("conflicts cast: {}", conflicts.size());

                    matchScores = castMatchScores(matched);
                    log.info("matchScores cast: {}", matchScores != null ? "not null" : "NULL");

                    investors = collectInvestors(companies);
                    log.info("investors collected: {}", investors.size());

                    sectors = sectorsFound;
                    log.info("Sector search complete: companies={}, conflicts={}, investors={}",
                            companies.size(), conflicts.size(), investors.size());
                }
                case "country" -> {
                    log.info("Entered country case block");
                    log.info("Processing country search for: {}", query);

                    try {
                        List<Country> countriesFound = findCountriesByName(query);
                        log.info("countriesFound size: {}", countriesFound.size());

                        if (countriesFound.isEmpty()) {
                            log.info("Returning response: query={}, type={}, totalMatches=0 (no countries found)",
                                    query, searchType);
                            return ResponseEntity.ok(emptySearchResponse(query, searchType, requestId));
                        }

                        countries = countriesFound;

                        Set<String> countryNames = countriesFound.stream().map(Country::getName)
                                .filter(Objects::nonNull).map(String::toLowerCase).collect(Collectors.toSet());
                        log.info("countryNames: {}", countryNames);

                        companies = intelligenceCompanyRepository.findAll().stream()
                                .filter(c -> c != null && c.getHeadquarters() != null
                                        && c.getHeadquarters().getName() != null
                                        && countryNames.contains(c.getHeadquarters().getName().toLowerCase()))
                                .toList();
                        log.info("companies found: {}", companies.size());

                        Map<String, Object> matched = matchConflictsForCompanies(companies);
                        log.info("matched result: {}", matched != null ? "not null" : "NULL");

                        List<Conflict> viaCompanies = castConflicts(matched);
                        log.info("viaCompanies conflicts: {}", viaCompanies.size());

                        matchScores = castMatchScores(matched);
                        log.info("matchScores cast: {}", matchScores != null ? "not null" : "NULL");

                        List<Conflict> directConflicts = conflictRepository.findByInvolvedCountryNamesIgnoreCase(countryNames);
                        log.info("directConflicts: {}", directConflicts.size());

                        conflicts = dedupeConflicts(Stream.concat(viaCompanies.stream(), directConflicts.stream())
                                .collect(Collectors.toList()));
                        log.info("conflicts deduped: {}", conflicts.size());

                        investors = collectInvestors(companies);
                        log.info("investors collected: {}", investors.size());

                        sectors = collectSectors(companies);
                        log.info("sectors collected: {}", sectors.size());
                    } catch (Exception e) {
                        log.error("Exception in country case", e);
                        return ResponseEntity.ok(emptySearchResponse(query, searchType, requestId));
                    }

                    log.info("Country search complete: companies={}, conflicts={}, investors={}",
                            companies.size(), conflicts.size(), investors.size());
                }
                case "conflict" -> {
                    log.info("Processing conflict search for: {}", query);
                    List<Conflict> conflictsFound;
                    Long conflictId = tryParseLong(query);
                    if (conflictId != null) {
                        Optional<Conflict> byId = conflictRepository.findById(conflictId);
                        if (byId.isEmpty()) {
                            log.info("Returning response: query={}, type={}, totalMatches=0 (no conflict with id {})",
                                    query, searchType, conflictId);
                            return ResponseEntity.ok(emptySearchResponse(query, searchType, requestId));
                        }
                        conflictsFound = List.of(byId.get());
                    } else {
                        conflictsFound = findConflictsByNameOrDescription(query);
                        if (conflictsFound.isEmpty()) {
                            log.info("Returning response: query={}, type={}, totalMatches=0 (no conflicts found)",
                                    query, searchType);
                            return ResponseEntity.ok(emptySearchResponse(query, searchType, requestId));
                        }
                    }

                    List<Company> matchedCompanies = new ArrayList<>();
                    Set<String> investorNames = new LinkedHashSet<>();
                    Map<String, Integer> mergedScores = new LinkedHashMap<>();
                    for (Conflict c : conflictsFound) {
                        Map<String, Object> analysis = conflictCompanyMatcher.analyzeConflict(c);
                        List<Company> analyzedCompanies = castCompanies(analysis);
                        matchedCompanies.addAll(analyzedCompanies);
                        Object investorObj = analysis.get("investors");
                        if (investorObj instanceof Collection<?> col) {
                            col.forEach(n -> {
                                if (n instanceof String s) investorNames.add(s);
                            });
                        }
                        castMatchScores(analysis).forEach((k, v) -> mergedScores.merge(k, v, Integer::sum));
                    }
                    companies = dedupeCompanies(matchedCompanies);
                    conflicts = conflictsFound;
                    matchScores = mergedScores;
                    investors = resolveInvestorsByNames(investorNames);
                    sectors = collectSectors(companies);
                    log.info("Conflict search complete: companies={}, conflicts={}, investors={}",
                            companies.size(), conflicts.size(), investors.size());
                }
                case "investor" -> {
                    log.info("Processing investor search for: {}", query);
                    List<Investor> investorsFound = findInvestorsByName(query);
                    if (investorsFound.isEmpty()) {
                        log.info("Returning response: query={}, type={}, totalMatches=0 (no investors found)",
                                query, searchType);
                        return ResponseEntity.ok(emptySearchResponse(query, searchType, requestId));
                    }
                    companies = dedupeCompanies(investorsFound.stream()
                            .flatMap(i -> i.getInvestments() == null ? Stream.empty() : i.getInvestments().stream())
                            .collect(Collectors.toList()));
                    Map<String, Object> matched = matchConflictsForCompanies(companies);
                    conflicts = castConflicts(matched);
                    matchScores = castMatchScores(matched);
                    investors = investorsFound;
                    sectors = collectSectors(companies);
                    log.info("Investor search complete: companies={}, conflicts={}, investors={}",
                            companies.size(), conflicts.size(), investors.size());
                }
                case "supply" -> {
                    log.info("Processing supply search for: {}", query);
                    List<SupplyChain> routesFound = findSupplyChainsByCommodity(query);
                    if (routesFound.isEmpty()) {
                        log.info("Returning response: query={}, type={}, totalMatches=0 (no supply routes found)",
                                query, searchType);
                        return ResponseEntity.ok(emptySearchResponse(query, searchType, requestId));
                    }
                    companies = dedupeCompanies(routesFound.stream()
                            .flatMap(r -> Stream.of(r.getSupplier(), r.getRecipient()))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList()));
                    Map<String, Object> matched = matchConflictsForCompanies(companies);
                    conflicts = castConflicts(matched);
                    matchScores = castMatchScores(matched);
                    investors = collectInvestors(companies);
                    sectors = collectSectors(companies);
                    log.info("Supply search complete: companies={}, conflicts={}, investors={}",
                            companies.size(), conflicts.size(), investors.size());
                }
                default -> {
                    log.warn("Returning response: query={}, type={} unsupported", query, searchType);
                    return ResponseEntity.badRequest().body(Map.of("error", "Unsupported type: " + searchType));
                }
            }

            int totalMatches = companies.size() + conflicts.size() + investors.size()
                    + sectors.size() + countries.size();
            boolean loading = totalMatches == 0;

            Map<String, Object> results = new LinkedHashMap<>();
            results.put("companies", capList(companies, SEARCH_RESULT_LIMIT));
            results.put("conflicts", capList(conflicts, SEARCH_RESULT_LIMIT));
            results.put("investors", capList(investors, SEARCH_RESULT_LIMIT));
            results.put("sectors", capList(sectors, SEARCH_RESULT_LIMIT));
            results.put("countries", capList(countries, SEARCH_RESULT_LIMIT));
            results.put("matchScores", matchScores);

            if (loading) {
                asyncDataFetchService.fetchMissingData(query, searchType, requestId);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("query", query);
            response.put("type", searchType);
            response.put("results", results);
            response.put("loading", loading);
            response.put("requestId", requestId);
            response.put("totalMatches", totalMatches);

            log.info("Returning response: query={}, type={}, totalMatches={}, loading={}, requestId={}",
                    query, searchType, totalMatches, loading, requestId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Intel search failed for q='{}', type='{}'", query, searchType, e);
            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("error", "Search failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            log.info("Returning response: query={}, type={}, error={}", query, searchType, errorBody.get("error"));
            return ResponseEntity.badRequest().body(errorBody);
        }
      } catch (Throwable e) {
          log.error("FULL NPE STACK TRACE:", e);
          return ResponseEntity.badRequest().body(Map.of("error", e.getClass().getName() + ": " + e.getMessage()));
      }
    }

    private Map<String, Object> emptySearchResponse(String query, String searchType, String requestId) {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("companies", List.of());
        results.put("conflicts", List.of());
        results.put("investors", List.of());
        results.put("sectors", List.of());
        results.put("countries", List.of());
        results.put("matchScores", Map.of());

        asyncDataFetchService.fetchMissingData(query, searchType, requestId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("type", searchType);
        response.put("results", results);
        response.put("loading", true);
        response.put("requestId", requestId);
        response.put("totalMatches", 0);
        return response;
    }

    private List<Company> findCompaniesByTickerOrName(String query) {
        String lower = query.toLowerCase();
        List<Company> allCompanies = intelligenceCompanyRepository.findAll();
        if (allCompanies == null) {
            log.warn("intelligenceCompanyRepository.findAll() returned null");
            return List.of();
        }
        return allCompanies.stream()
                .filter(c -> c != null && (
                        (c.getTicker() != null && c.getTicker().equalsIgnoreCase(query))
                                || (c.getName() != null && c.getName().toLowerCase().contains(lower))))
                .toList();
    }

    private List<Sector> findSectorsByName(String query) {
        String lower = query.toLowerCase();
        List<Sector> allSectors = sectorRepository.findAll();
        if (allSectors == null) {
            log.warn("sectorRepository.findAll() returned null");
            return List.of();
        }
        return allSectors.stream()
                .filter(s -> s != null && s.getName() != null && s.getName().toLowerCase().contains(lower))
                .toList();
    }

    private List<Country> findCountriesByName(String query) {
        String lower = query.toLowerCase();
        List<Country> allCountries = countryRepository.findAll();
        if (allCountries == null) {
            log.warn("countryRepository.findAll() returned null");
            return List.of();
        }
        return allCountries.stream()
                .filter(c -> c != null && c.getName() != null && c.getName().toLowerCase().contains(lower))
                .toList();
    }

    private List<Conflict> findConflictsByNameOrDescription(String query) {
        String lower = query.toLowerCase();
        List<Conflict> allConflicts = conflictRepository.findAll();
        if (allConflicts == null) {
            log.warn("conflictRepository.findAll() returned null");
            return List.of();
        }
        return allConflicts.stream()
                .filter(c -> c != null && (
                        (c.getName() != null && c.getName().toLowerCase().contains(lower))
                                || (c.getDescription() != null && c.getDescription().toLowerCase().contains(lower))))
                .toList();
    }

    private List<Investor> findInvestorsByName(String query) {
        String lower = query.toLowerCase();
        List<Investor> allInvestors = investorRepository.findAll();
        if (allInvestors == null) {
            log.warn("investorRepository.findAll() returned null");
            return List.of();
        }
        return allInvestors.stream()
                .filter(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains(lower))
                .toList();
    }

    private List<SupplyChain> findSupplyChainsByCommodity(String query) {
        String lower = query.toLowerCase();
        List<SupplyChain> allRoutes = supplyChainRepository.findAll();
        if (allRoutes == null) {
            log.warn("supplyChainRepository.findAll() returned null");
            return List.of();
        }
        return allRoutes.stream()
                .filter(r -> r != null && r.getCommodity() != null && r.getCommodity().toLowerCase().contains(lower))
                .toList();
    }

    private Long tryParseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Scores companies against every known conflict via ConflictCompanyMatcher, keeping
     * only the conflicts that actually matched one of the target companies.
     */
    private Map<String, Object> matchConflictsForCompanies(List<Company> targetCompanies) {
        // With no target companies, every conflict is guaranteed to have zero overlap - skip the
        // findAll()-and-analyze pass entirely instead of running analyzeConflict() against every
        // conflict in the database (13,000+ and growing every 15 minutes) just to prove that.
        // This was the actual multi-minute bottleneck behind the country/sector search endpoints.
        if (targetCompanies.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("conflicts", List.<Conflict>of());
            empty.put("matchScores", Map.<String, Integer>of());
            return empty;
        }

        Set<Long> targetIds = targetCompanies.stream().map(Company::getId)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        List<Conflict> matchedConflicts = new ArrayList<>();
        Map<String, Integer> matchScores = new LinkedHashMap<>();

        for (Conflict conflict : conflictRepository.findAll()) {
            Map<String, Object> analysis = conflictCompanyMatcher.analyzeConflict(conflict);
            List<Company> analyzedCompanies = castCompanies(analysis);
            Map<String, Integer> scores = castMatchScores(analysis);

            List<Company> overlap = analyzedCompanies.stream()
                    .filter(c -> c.getId() != null && targetIds.contains(c.getId()))
                    .toList();

            if (!overlap.isEmpty()) {
                matchedConflicts.add(conflict);
                overlap.forEach(c -> matchScores.merge(
                        c.getName(), scores.getOrDefault(c.getName(), 0), Integer::sum));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conflicts", matchedConflicts);
        result.put("matchScores", matchScores);
        return result;
    }

    private List<Investor> collectInvestors(List<Company> companies) {
        Map<Long, Investor> byId = new LinkedHashMap<>();
        for (Company company : companies) {
            if (company == null || company.getInvestors() == null) continue;
            for (Investor investor : company.getInvestors()) {
                if (investor != null && investor.getId() != null) {
                    byId.putIfAbsent(investor.getId(), investor);
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    private List<Investor> resolveInvestorsByNames(Set<String> names) {
        List<Investor> resolved = new ArrayList<>();
        for (String name : names) {
            investorRepository.findByName(name).ifPresent(resolved::add);
        }
        return resolved;
    }

    private List<Sector> collectSectors(List<Company> companies) {
        Map<Long, Sector> byId = new LinkedHashMap<>();
        for (Company company : companies) {
            if (company != null && company.getSector() != null && company.getSector().getId() != null) {
                byId.putIfAbsent(company.getSector().getId(), company.getSector());
            }
        }
        return new ArrayList<>(byId.values());
    }

    private List<Company> dedupeCompanies(Collection<Company> companies) {
        Map<Long, Company> byId = new LinkedHashMap<>();
        for (Company company : companies) {
            if (company != null && company.getId() != null) {
                byId.putIfAbsent(company.getId(), company);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private List<Conflict> dedupeConflicts(Collection<Conflict> conflicts) {
        Map<Long, Conflict> byId = new LinkedHashMap<>();
        for (Conflict conflict : conflicts) {
            if (conflict != null && conflict.getId() != null) {
                byId.putIfAbsent(conflict.getId(), conflict);
            }
        }
        return new ArrayList<>(byId.values());
    }

    @SuppressWarnings("unchecked")
    private List<Company> castCompanies(Map<String, Object> analysis) {
        Object companiesObj = analysis.get("companies");
        return companiesObj instanceof List<?> list ? (List<Company>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Conflict> castConflicts(Map<String, Object> matched) {
        Object conflictsObj = matched.get("conflicts");
        return conflictsObj instanceof List<?> list ? (List<Conflict>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> castMatchScores(Map<String, Object> analysis) {
        Object scoresObj = analysis.get("matchScores");
        return scoresObj instanceof Map<?, ?> map ? (Map<String, Integer>) map : Map.of();
    }

    private <T> List<T> capList(List<T> list, int max) {
        if (list == null) return List.of();
        return list.stream().limit(max).toList();
    }

    @PostMapping("/ingest/companies")
    public ResponseEntity<Map<String, Object>> ingestCompanies() {
        log.info("ingestCompanies endpoint called");
        try {
            log.info("Calling companyIngestionService.ingestCompaniesFromFinnhub()");
            Map<String, Object> result = companyIngestionService.ingestCompaniesFromFinnhub();
            log.info("Received result from ingestCompaniesFromFinnhub(): {}", result);
            return ResponseEntity.ok(result);
        } catch (Throwable t) {
            log.error("ingestCompanies endpoint failed", t);

            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("status", "error");
            errorBody.put("message", t.getMessage());
            errorBody.put("type", t.getClass().getName());
            return ResponseEntity.ok(errorBody);
        }
    }

    @PostMapping("/ingest/conflicts")
    public ResponseEntity<?> ingestConflicts() {
        Map<String, Object> result = gdeltIngestionService.ingestLatestGdeltEvents();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/analyze-conflict/{conflictId}")
    public ResponseEntity<?> analyzeConflict(@PathVariable Long conflictId) {
        return conflictRepository.findById(conflictId)
                .<ResponseEntity<?>>map(conflict -> ResponseEntity.ok(conflictCompanyMatcher.analyzeConflict(conflict)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/company/{name}")
    public ResponseEntity<?> getCompanyProfile(@PathVariable String name) {
        java.util.Optional<com.vyomin.core_api.model.intelligencegraph.Company> company =
                intelligenceCompanyRepository.findByName(name);
        return company.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/conflicts")
    public ResponseEntity<?> getActiveConflicts() {
        return ResponseEntity.ok(conflictRepository.findAll());
    }

    // Lightweight count for UI tiles that only need a number - findAll() above hydrates every
    // Conflict's relationships and gets slower as GDELT ingestion grows the table.
    @GetMapping("/conflicts/count")
    public ResponseEntity<?> getActiveConflictsCount() {
        return ResponseEntity.ok(java.util.Map.of("count", conflictRepository.count()));
    }

    @GetMapping("/supply-chain/{companyName}")
    public ResponseEntity<?> getSupplyChainForCompany(@PathVariable String companyName) {
        java.util.Optional<com.vyomin.core_api.model.intelligencegraph.Company> company =
                intelligenceCompanyRepository.findByName(companyName);
        if (company.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        java.util.List<com.vyomin.core_api.model.intelligencegraph.SupplyChain> routes =
                supplyChainRepository.findAll().stream()
                        .filter(route -> company.get().equals(route.getSupplier())
                                || company.get().equals(route.getRecipient()))
                        .collect(java.util.stream.Collectors.toList());

        return ResponseEntity.ok(routes);
    }

    @GetMapping("/risk-path/{personName}")
    public ResponseEntity<?> getRiskPath(@PathVariable String personName) {
        java.util.List<Object> pathNodes = graphCompanyRepository.findRiskPathByPersonName(personName);
        
        java.util.List<java.util.Map<String, Object>> nodes = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> links = new java.util.ArrayList<>();
        
        if (pathNodes != null && !pathNodes.isEmpty()) {
            // Process Neo4j path array into nodes and links
            for (int i = 0; i < pathNodes.size(); i++) {
                java.util.Map<String, Object> nodeProps = (java.util.Map<String, Object>) pathNodes.get(i);
                String nodeId = (String) nodeProps.getOrDefault("name", "Unknown Node " + i);
                
                java.util.Map<String, Object> node = new java.util.HashMap<>(nodeProps);
                node.put("id", nodeId);
                if (!node.containsKey("label")) node.put("label", "Entity");
                nodes.add(node);
                
                if (i > 0) {
                    java.util.Map<String, Object> prevNodeProps = (java.util.Map<String, Object>) pathNodes.get(i - 1);
                    String prevNodeId = (String) prevNodeProps.getOrDefault("name", "Unknown Node " + (i - 1));
                    
                    java.util.Map<String, Object> link = new java.util.HashMap<>();
                    link.put("source", prevNodeId);
                    link.put("target", nodeId);
                    links.add(link);
                }
            }
        } else {
            // FALLBACK DUMMY DATA if Neo4j is empty (e.g. GDELT rate limits)
            nodes.add(java.util.Map.of("id", "defaultUser", "name", "John Doe (Operative)", "label", "Person", "color", "#3b82f6"));
            nodes.add(java.util.Map.of("id", "shell1", "name", "Global Trade LLC", "label", "Company", "color", "#eab308"));
            nodes.add(java.util.Map.of("id", "shell2", "name", "Pacific Holdings", "label", "Company", "color", "#eab308"));
            nodes.add(java.util.Map.of("id", "target", "name", "Restricted Entity X", "label", "Sanctioned", "color", "#ef4444"));
            
            links.add(java.util.Map.of("source", "defaultUser", "target", "shell1"));
            links.add(java.util.Map.of("source", "shell1", "target", "shell2"));
            links.add(java.util.Map.of("source", "shell2", "target", "target"));
        }
        
        java.util.Map<String, Object> graphData = new java.util.HashMap<>();
        graphData.put("nodes", nodes);
        graphData.put("links", links);
        
        return ResponseEntity.ok(graphData);
    }
}
