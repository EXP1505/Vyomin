package com.vyomin.core_api.controller;

import com.vyomin.core_api.model.telemetry.FlightTelemetry;
import com.vyomin.core_api.service.FlightTelemetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final FlightTelemetryService flightTelemetryService;

    @GetMapping("/flights")
    public ResponseEntity<List<FlightTelemetry>> getLatestFlights() {
        return ResponseEntity.ok(flightTelemetryService.getLatestFlights());
    }
}
