package com.vyomin.core_api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledIngestionService {

    private final CompanyIngestionService companyIngestionService;
    private final GdeltIngestionService gdeltIngestionService;

    @Scheduled(cron = "0 0 2 * * *")
    public void runDailyIngestion() {
        log.info("Starting scheduled daily ingestion run...");

        try {
            companyIngestionService.ingestCompaniesFromFinnhub();
            log.info("Scheduled company ingestion completed successfully.");
        } catch (Exception e) {
            log.error("Scheduled company ingestion failed: {}", e.getMessage(), e);
        }

        try {
            gdeltIngestionService.ingestLatestGdeltEvents();
            log.info("Scheduled GDELT ingestion completed successfully.");
        } catch (Exception e) {
            log.error("Scheduled GDELT ingestion failed: {}", e.getMessage(), e);
        }

        log.info("Scheduled daily ingestion run finished.");
    }
}
