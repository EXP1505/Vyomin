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
public class AircraftDetails implements Serializable {
    private String icao24;
    private String registration;
    private String manufacturerName;
    private String model;
    private String operator;
}
