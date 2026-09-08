package com.vyomin.core_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Backs AircraftDatabaseService's lookups. Previously the whole ~100MB OpenSky aircraft CSV was
// parsed into an in-memory ConcurrentHashMap at startup - fine on a beefy box, but on a
// memory-capped deploy target (e.g. Render's free 512MB web service) that alone was enough to
// OOM the JVM before Spring even finished starting. Row lives in Postgres instead and is looked
// up per-flight by indexed PK - negligible latency, no heap footprint.
@Entity
@Table(name = "aircraft_details")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AircraftDetailsEntity {
    @Id
    @Column(name = "icao24")
    private String icao24;

    @Column(name = "registration")
    private String registration;

    @Column(name = "manufacturer_name")
    private String manufacturerName;

    @Column(name = "model")
    private String model;

    @Column(name = "operator")
    private String operator;
}
