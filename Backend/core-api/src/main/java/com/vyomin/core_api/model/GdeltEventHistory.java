package com.vyomin.core_api.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "gdelt_event_history", indexes = {
        @Index(name = "idx_gdelt_event_history_event_date", columnList = "event_date"),
        @Index(name = "idx_gdelt_event_history_type_region_date", columnList = "event_type, region, event_date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GdeltEventHistory {

    @Id
    @Column(name = "gdelt_event_id", nullable = false)
    private Long gdeltEventId;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "event_name")
    private String eventName;

    @Column(name = "region")
    private String region;

    @Column(name = "actor1_name")
    private String actor1Name;

    @Column(name = "actor1_country_code")
    private String actor1CountryCode;

    @Column(name = "actor2_name")
    private String actor2Name;

    @Column(name = "actor2_country_code")
    private String actor2CountryCode;

    @Column(name = "severity_score")
    private BigDecimal severityScore;

    @Column(name = "tone")
    private BigDecimal tone;

    @Column(name = "source_url")
    private String sourceUrl;

    // Plain column, no columnDefinition: a "TIMESTAMP DEFAULT now()" columnDefinition makes
    // Hibernate's ddl-auto=update re-issue "ALTER COLUMN ingested_at SET DATA TYPE TIMESTAMP
    // DEFAULT now()" on every boot, which Postgres rejects (DEFAULT isn't valid in a SET DATA
    // TYPE clause). GdeltEventHistoryRepository.upsert() sets this column explicitly with now()
    // instead, so no DB-level default is needed.
    @Column(name = "ingested_at")
    private Instant ingestedAt;
}