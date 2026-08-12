package com.vyomin.core_api.config;

import com.vyomin.core_api.service.GdeltHistoricalBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Manual trigger only. Runs the GDELT historical backfill once on startup when the
 * "gdelt-backfill" Spring profile is active (e.g.
 * java -jar core-api.jar --spring.profiles.active=gdelt-backfill), and never otherwise - it must
 * not fire during normal application startup, and is independent of the live 15-min/daily GDELT
 * ingestion in GdeltIngestionService/ScheduledIngestionService.
 */
@Component
@Profile("gdelt-backfill")
@RequiredArgsConstructor
@Slf4j
public class GdeltBackfillRunner implements CommandLineRunner {

    private final GdeltHistoricalBackfillService gdeltHistoricalBackfillService;

    @Override
    public void run(String... args) {
        log.info("Running GDELT historical backfill (profile=gdelt-backfill)...");
        Map<String, Object> summary = gdeltHistoricalBackfillService.ingestHistoricalRange(null, null);
        log.info("GDELT historical backfill finished: {}", summary);
    }
}