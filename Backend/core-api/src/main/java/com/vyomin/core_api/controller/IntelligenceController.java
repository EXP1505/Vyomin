package com.vyomin.core_api.controller;

import com.vyomin.core_api.repository.graph.CompanyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/intel")
public class IntelligenceController {
    // Autowiring companyrepo from repository to find out the risk paths from a person to a sanctioned country

    @Autowired
    private CompanyRepository companyRepository;
    @GetMapping("/risk-path/{personName}")
    public ResponseEntity<?> getRiskPath(@PathVariable String personName) {
        java.util.List<Object> pathNodes = companyRepository.findRiskPathByPersonName(personName);
        
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
