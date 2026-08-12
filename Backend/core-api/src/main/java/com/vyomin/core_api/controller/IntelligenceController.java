package com.vyomin.core_api.controller;

import com.vyomin.core_api.repository.graph.GraphCompanyRepository;
import com.vyomin.core_api.repository.intelligencegraph.ConflictRepository;
import com.vyomin.core_api.repository.intelligencegraph.SupplyChainRepository;
import com.vyomin.core_api.service.CompanyIngestionService;
import com.vyomin.core_api.service.ConflictCompanyMatcher;
import com.vyomin.core_api.service.GdeltIngestionService;
import com.vyomin.core_api.service.IntelligenceSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

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
    private IntelligenceSearchService intelligenceSearchService;

    private static final int SEARCH_MAX_ATTEMPTS = 3;
    private static final long SEARCH_RETRY_BACKOFF_MS = 300;

    // Delegates to IntelligenceSearchService (a separate Spring bean, so @Transactional actually
    // applies per attempt - a private method here would bypass the proxy on self-invocation) and
    // retries a bounded number of times if AuraDB drops the connection mid-request. That failure
    // mode - "Failed to write messages" from the Neo4j driver - is transient (pool exhaustion,
    // idle-timeout, a momentary network blip) and usually clears within a retry or two, so it
    // shouldn't be handed to the client as a hard error on the first hit.
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("q") String q,
                                     @RequestParam("type") String type) {
        for (int attempt = 1; attempt <= SEARCH_MAX_ATTEMPTS; attempt++) {
            try {
                return intelligenceSearchService.search(q, type);
            } catch (Exception e) {
                if (!IntelligenceSearchService.isTransientNeo4jError(e) || attempt == SEARCH_MAX_ATTEMPTS) {
                    log.error("Intel search failed for q='{}', type='{}' (attempt {}/{})",
                            q, type, attempt, SEARCH_MAX_ATTEMPTS, e);
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error",
                            "Search temporarily unavailable - the database connection was interrupted. Please try again."));
                }
                log.warn("Transient Neo4j error on search attempt {}/{} for q='{}', type='{}': {} - retrying",
                        attempt, SEARCH_MAX_ATTEMPTS, q, type, e.getMessage());
                try {
                    Thread.sleep(SEARCH_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(Map.of("error", "Search interrupted, please try again."));
                }
            }
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Search temporarily unavailable, please try again."));
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

    // Bounded most-recent-first read for the Home page's live globe flashpoints and signal feed -
    // see findTop300ByOrderByDateReportedDesc for why this doesn't use findAll() above.
    @GetMapping("/conflicts/recent")
    public ResponseEntity<?> getRecentConflicts() {
        return ResponseEntity.ok(conflictRepository.findTop40ByOrderByDateReportedDesc());
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
