package com.vyomin.core_api.model.telemetry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder

//right now i am going for callsign as the unique identifier as rn i am testing the feature
public class FlightTelemetry implements Serializable {
    private String callsign;
    private double latitude;
    private double longitude;
    private double altitude;
    private double heading;
    private long timestamp;
    // classified type: MILITARY, COMMERCIAL, CARGO, PRIVATE, HELICOPTER, DRONE, UNKNOWN
    private String flightType;
    private String aircraftModel;
    private String registration;
}
