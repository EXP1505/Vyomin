package com.vyomin.core_api.repository;

import com.vyomin.core_api.model.GdeltEventHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
                 severity_score, tone, source_url, ingested_at)
            VALUES (:gdeltEventId, :eventDate, :eventType, :eventName, :region,
                    :actor1Name, :actor1CountryCode, :actor2Name, :actor2CountryCode,
                    :severityScore, :tone, :sourceUrl, now())
            ON CONFLICT (gdelt_event_id) DO NOTHING
            """, nativeQuery = true)
    void upsert(Long gdeltEventId, LocalDate eventDate, String eventType, String eventName, String region,
                String actor1Name, String actor1CountryCode, String actor2Name, String actor2CountryCode,
                BigDecimal severityScore, BigDecimal tone, String sourceUrl);

    // Multiple raw rows sharing (event_type, actor1_country_code, actor2_country_code, event_date)
    // are the same real-world event reported by multiple articles, not separate events - this
    // collapses them to one row per group. Rows missing either actor's country code are excluded
    // (an event needs both actors identified to be usable for the analyzer); countRawRows() below
    // (called without that exclusion) is what lets callers measure the size of that gap.
    @Query(value = """
            SELECT event_type AS eventType,
                   actor1_country_code AS actor1CountryCode,
                   actor2_country_code AS actor2CountryCode,
                   event_date AS eventDate,
                   avg(severity_score) AS avgSeverity,
                   avg(tone) AS avgTone,
                   count(*) AS articleCount,
                   max(source_url) AS sourceUrl
            FROM gdelt_event_history
            WHERE actor1_country_code IS NOT NULL AND actor1_country_code <> ''
              AND actor2_country_code IS NOT NULL AND actor2_country_code <> ''
              AND (:eventType IS NULL OR event_type = :eventType)
              AND (:actor1CountryCode IS NULL OR actor1_country_code = :actor1CountryCode)
              AND (:actor2CountryCode IS NULL OR actor2_country_code = :actor2CountryCode)
              AND event_date BETWEEN :dateFrom AND :dateTo
            GROUP BY event_type, actor1_country_code, actor2_country_code, event_date
            ORDER BY event_date
            """, nativeQuery = true)
    List<DyadEventProjection> findDyadEvents(@Param("eventType") String eventType,
                                              @Param("actor1CountryCode") String actor1CountryCode,
                                              @Param("actor2CountryCode") String actor2CountryCode,
                                              @Param("dateFrom") LocalDate dateFrom,
                                              @Param("dateTo") LocalDate dateTo);

    // Same filter as findDyadEvents but without the actor-not-null exclusion, so
    // (countRawRows - sum(articleCount) across findDyadEvents' result) tells you how many raw rows
    // were excluded for missing actor identification.
    @Query(value = """
            SELECT count(*)
            FROM gdelt_event_history
            WHERE (:eventType IS NULL OR event_type = :eventType)
              AND (:actor1CountryCode IS NULL OR actor1_country_code = :actor1CountryCode)
              AND (:actor2CountryCode IS NULL OR actor2_country_code = :actor2CountryCode)
              AND event_date BETWEEN :dateFrom AND :dateTo
            """, nativeQuery = true)
    long countRawRows(@Param("eventType") String eventType,
                       @Param("actor1CountryCode") String actor1CountryCode,
                       @Param("actor2CountryCode") String actor2CountryCode,
                       @Param("dateFrom") LocalDate dateFrom,
                       @Param("dateTo") LocalDate dateTo);
}