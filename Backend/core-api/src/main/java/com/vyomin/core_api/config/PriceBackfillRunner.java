package com.vyomin.core_api.config;

import com.vyomin.core_api.service.PriceBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Manual trigger only. Runs the Stooq price backfill once on startup when the "backfill" Spring
 * profile is active (e.g. java -jar core-api.jar --spring.profiles.active=backfill), and never
 * otherwise - it must not fire during normal application startup.
 */
@Component
@Profile("backfill")
@RequiredArgsConstructor
@Slf4j
public class PriceBackfillRunner implements CommandLineRunner {

    private final PriceBackfillService priceBackfillService;

    @Override
    public void run(String... args) {
        log.info("Running Stooq price backfill (profile=backfill)...");
        Map<String, Object> summary = priceBackfillService.backfill(null, null);
        log.info("Stooq price backfill finished: {}", summary);
    }
}