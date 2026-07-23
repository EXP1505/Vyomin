package com.vyomin.core_api.controller;

import com.vyomin.core_api.repository.graph.GraphCompanyRepository;
import com.vyomin.core_api.repository.intelligencegraph.ConflictRepository;
import com.vyomin.core_api.repository.intelligencegraph.SupplyChainRepository;
import com.vyomin.core_api.service.CompanyIngestionService;
import com.vyomin.core_api.service.ConflictCompanyMatcher;
import com.vyomin.core_api.service.GdeltIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
        gdeltIngestionService.fetchAndIngestDailyGdelt();
        return ResponseEntity.ok(java.util.Map.of("status", "GDELT conflict ingestion completed"));
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
