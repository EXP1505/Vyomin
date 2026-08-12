package com.vyomin.core_api.repository;

import com.vyomin.core_api.model.GdeltEventHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface GdeltEventHistoryRepository extends JpaRepository<GdeltEventHistory, Long> {

    // Append-only history: ON CONFLICT DO NOTHING (not DO UPDATE like price_daily) since a given
    // GDELT event's data doesn't change once published, and re-running a backfill over already-
    // ingested files should be a no-op rather than rewrite rows.
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO gdelt_event_history
                (gdelt_event_id, event_date, event_type, event_name, region,
                 actor1_name, actor1_country_code, actor2_name, actor2_country_code,
                 severity_score, tone, source_url)
            VALUES (:gdeltEventId, :eventDate, :eventType, :eventName, :region,
                    :actor1Name, :actor1CountryCode, :actor2Name, :actor2CountryCode,
                    :severityScore, :tone, :sourceUrl)
            ON CONFLICT (gdelt_event_id) DO NOTHING
            """, nativeQuery = true)
    void upsert(Long gdeltEventId, LocalDate eventDate, String eventType, String eventName, String region,
                String actor1Name, String actor1CountryCode, String actor2Name, String actor2CountryCode,
                BigDecimal severityScore, BigDecimal tone, String sourceUrl);
}