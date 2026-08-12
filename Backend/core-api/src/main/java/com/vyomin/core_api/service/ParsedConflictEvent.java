package com.vyomin.core_api.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Source-agnostic result of parsing one GDELT 2.0 Events row. Built once by
 * GdeltIngestionService.parseRow() from the CAMEO/actor/date/severity fields in a raw tab-
 * delimited row, and consumed by both the live Neo4j Conflict path (GdeltIngestionService) and
 * the historical Postgres backfill path (GdeltHistoricalBackfillService) - the parsing itself is
 * never duplicated between the two.
 *
 * goldstein is the raw -10..+10 Goldstein scale value (negative = conflictual, positive =
 * cooperative) before it's mapped into severityScore's 0..100 range - kept here so the backfill's
 * min-severity-abs filter can threshold on the original signed scale.
 */
public record ParsedConflictEvent(
        String gdeltEventId,
        LocalDate eventDate,
        String eventType,
        Integer eventCode,
        String name,
        String description,
        Integer severityScore,
        double goldstein,
        Double tone,
        Double latitude,
        Double longitude,
        String actor1Name,
        String actor1CountryCode,
        String actor1Type,
        String actor2Name,
        String actor2CountryCode,
        String actor2Type,
        String primaryRegion,
        String sourceUrl,
        List<String> keywords,
        Set<String> involvedCountryNames
) {
}