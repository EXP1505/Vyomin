package com.vyomin.core_api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Plain 200 endpoint for uptime monitors (e.g. UptimeRobot) to ping. The bare "/" root has no
// mapping and correctly 404s - that's fine for a pure API service, but it means an uptime monitor
// pointed at "/" reports the service as permanently "down" even while it's healthy. Point the
// monitor at this endpoint instead.
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
