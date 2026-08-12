package com.vyomin.core_api.service;

import com.vyomin.core_api.model.GdeltBackfillCheckpoint;
import com.vyomin.core_api.repository.GdeltBackfillCheckpointRepository;
import com.vyomin.core_api.repository.GdeltEventHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backfills historical GDELT 2.0 Events into Postgres (gdelt_event_history) instead of Neo4j, so
 * it's unaffected by AuraDB's node cap and pruneOldConflicts()'s 180-day retention - both of which
 * make Neo4j unsuitable for multi-month/multi-year history. Reuses GdeltIngestionService's row
 * parsing (isWhitelisted/buildDedupeKey/parseRow/unzipSingleEntry/ensureCameoLookupsLoaded)
 * unchanged; only the source of files (masterfilelist.txt range vs. the live "latest" manifest)
 * and the destination (Postgres upsert vs. Neo4j Conflict save) differ.
 *
 * Applies a tighter, backfill-specific severity filter on top of the live actor whitelist
 * (vyomin.analysis.backfill.min-severity-abs, on the raw -10..+10 Goldstein scale) because the
 * live whitelist alone produces on the order of 5-15k matching events/day - backfilling months at
 * that density would be millions of rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GdeltHistoricalBackfillService {

    private static final int TIMEOUT_MS = 60_000;
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_BACKOFF_MS = 3000;
    private static final int PROGRESS_LOG_INTERVAL_FILES = 100;
    private static final Integer CHECKPOINT_ID = 1;
    private static final DateTimeFormatter FILE_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final GdeltIngestionService gdeltIngestionService;
    private final GdeltEventHistoryRepository gdeltEventHistoryRepository;
    private final GdeltBackfillCheckpointRepository checkpointRepository;
    private final RestClient restClient = buildRestClient();

    @Value("${gdelt.manifest.master-list-url}")
    private String masterFileListUrl;

    @Value("${vyomin.analysis.backfill.min-severity-abs:5.0}")
    private double minSeverityAbs;

    @Value("${vyomin.analysis.backfill.file-delay-ms:1500}")
    private long fileDelayMs;

    @Value("${vyomin.analysis.backfill.start-date:}")
    private String configuredStartDate;

    @Value("${vyomin.analysis.backfill.end-date:}")
    private String configuredEndDate;

    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        return RestClient.builder().requestFactory(factory).build();
    }

    public Map<String, Object> ingestHistoricalRange(LocalDate from, LocalDate to) {
        LocalDate effectiveEnd = to != null ? to
                : (configuredEndDate.isBlank() ? LocalDate.now() : LocalDate.parse(configuredEndDate));
        LocalDate effectiveStart = from != null ? from
                : (configuredStartDate.isBlank() ? effectiveEnd.minusMonths(6) : LocalDate.parse(configuredStartDate));

        log.info("Starting GDELT historical backfill: range [{}, {}], min|goldstein|>={}",
                effectiveStart, effectiveEnd, minSeverityAbs);
        gdeltIngestionService.ensureCameoLookupsLoaded();

        List<String> allFiles;
        try {
            allFiles = fetchAndFilterMasterFileList(effectiveStart, effectiveEnd);
        } catch (Exception e) {
            log.error("Failed to download/filter GDELT master file list, aborting backfill: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }

        int totalFiles = allFiles.size();
        int startIndex = resolveStartIndex(allFiles);
        int filesToProcess = totalFiles - startIndex;

        long totalInserted = 0;
        long totalExcluded = 0;
        long totalMalformed = 0;
        long totalFailedRows = 0;
        int filesDone = 0;
        List<String> skippedFiles = new ArrayList<>();
        Instant runStart = Instant.now();

        for (int i = startIndex; i < totalFiles; i++) {
            String fileUrl = allFiles.get(i);
            try {
                FileOutcome outcome = processFile(fileUrl);
                totalInserted += outcome.inserted();
                totalExcluded += outcome.excluded();
                totalMalformed += outcome.malformed();
                totalFailedRows += outcome.failed();
            } catch (Exception e) {
                log.error("Skipping file after {} failed attempts: {} ({})", MAX_ATTEMPTS, fileUrl, e.getMessage());
                skippedFiles.add(fileUrl);
            }

            saveCheckpoint(fileUrl);
            filesDone++;

            if (filesDone % PROGRESS_LOG_INTERVAL_FILES == 0 || i == totalFiles - 1) {
                logProgress(filesDone, filesToProcess, totalInserted, runStart);
            }

            if (i < totalFiles - 1) {
                sleepQuietly(fileDelayMs);
            }
        }

        Duration elapsed = Duration.between(runStart, Instant.now());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "success");
        summary.put("rangeStart", effectiveStart);
        summary.put("rangeEnd", effectiveEnd);
        summary.put("totalFilesInRange", totalFiles);
        summary.put("filesProcessedThisRun", filesDone);
        summary.put("rowsInserted", totalInserted);
        summary.put("rowsExcludedBySeverity", totalExcluded);
        summary.put("rowsMalformed", totalMalformed);
        summary.put("rowsFailed", totalFailedRows);
        summary.put("skippedFiles", skippedFiles);
        summary.put("elapsedSeconds", elapsed.toSeconds());
        log.info("GDELT historical backfill finished: {}", summary);
        return summary;
    }

    private int resolveStartIndex(List<String> allFiles) {
        String checkpointFile = checkpointRepository.findById(CHECKPOINT_ID)
                .map(GdeltBackfillCheckpoint::getLastCompletedFile)
                .orElse(null);
        if (checkpointFile == null) {
            return 0;
        }
        int idx = allFiles.indexOf(checkpointFile);
        if (idx < 0) {
            log.warn("Checkpoint file {} not found in this run's file list (range/config changed?) - " +
                    "starting from the beginning of the range instead of resuming", checkpointFile);
            return 0;
        }
        log.info("Resuming from checkpoint: {} files already completed, {} remaining", idx + 1, allFiles.size() - idx - 1);
        return idx + 1;
    }

    private record FileOutcome(int inserted, int excluded, int malformed, int failed) {
    }

    private FileOutcome processFile(String zipUrl) throws Exception {
        byte[] zipBytes = downloadWithRetry(zipUrl);
        String csv = gdeltIngestionService.unzipSingleEntry(zipBytes);
        List<String> rows = csv.lines().filter(line -> !line.isBlank()).toList();

        int inserted = 0;
        int excluded = 0;
        int malformed = 0;
        int failed = 0;

        for (String row : rows) {
            String[] cols = row.split("\t", -1);
            if (cols.length < GdeltIngestionService.MIN_COLUMNS) {
                malformed++;
                continue;
            }
            if (!gdeltIngestionService.isWhitelisted(cols)) {
                continue;
            }

            String dedupeKey = gdeltIngestionService.buildDedupeKey(cols);
            ParsedConflictEvent event;
            try {
                event = gdeltIngestionService.parseRow(cols, dedupeKey);
            } catch (Exception e) {
                failed++;
                continue;
            }

            if (Math.abs(event.goldstein()) < minSeverityAbs) {
                excluded++;
                continue;
            }
            if (event.eventDate() == null) {
                failed++;
                continue;
            }

            try {
                gdeltEventHistoryRepository.upsert(
                        Long.parseLong(event.gdeltEventId()),
                        event.eventDate(),
                        event.eventType(),
                        event.name(),
                        event.primaryRegion(),
                        event.actor1Name(),
                        event.actor1CountryCode(),
                        event.actor2Name(),
                        event.actor2CountryCode(),
                        event.severityScore() != null ? BigDecimal.valueOf(event.severityScore()) : null,
                        event.tone() != null ? BigDecimal.valueOf(event.tone()) : null,
                        event.sourceUrl());
                inserted++;
            } catch (Exception e) {
                failed++;
            }
        }

        log.debug("File {}: rows={}, malformed={}, excludedBySeverity={}, inserted={}, failed={}",
                zipUrl, rows.size(), malformed, excluded, inserted, failed);
        return new FileOutcome(inserted, excluded, malformed, failed);
    }

    private byte[] downloadWithRetry(String url) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                byte[] bytes = restClient.get().uri(url).retrieve().body(byte[].class);
                if (bytes == null || bytes.length == 0) {
                    throw new IllegalStateException("empty response for " + url);
                }
                return bytes;
            } catch (Exception e) {
                lastError = e;
                log.warn("Download failed for {} (attempt {}/{}): {}", url, attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepQuietly(RETRY_BACKOFF_MS * attempt);
                }
            }
        }
        throw lastError;
    }

    private List<String> fetchAndFilterMasterFileList(LocalDate from, LocalDate to) {
        log.info("Downloading GDELT master file list: {}", masterFileListUrl);
        String body = restClient.get().uri(masterFileListUrl).retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("GDELT masterfilelist.txt was empty");
        }

        List<String> matches = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (!line.contains(".export.CSV.zip")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 3) {
                continue;
            }
            String url = parts[2];
            int slash = url.lastIndexOf('/');
            String filename = slash >= 0 ? url.substring(slash + 1) : url;
            if (filename.length() < 14) {
                continue;
            }
            try {
                LocalDate fileDate = LocalDateTime.parse(filename.substring(0, 14), FILE_TS_FORMAT).toLocalDate();
                if (!fileDate.isBefore(from) && !fileDate.isAfter(to)) {
                    matches.add(url);
                }
            } catch (Exception ignored) {
                // Unparseable filename timestamp on this one line - skip it rather than abort the
                // whole filter over a single malformed masterfilelist.txt entry.
            }
        }
        // URLs embed yyyyMMddHHmmss right after the last '/', so lexicographic order is
        // chronological order - no need to re-parse timestamps just to sort.
        matches.sort(Comparator.naturalOrder());
        log.info("Master file list: {} .export.CSV.zip entries in [{}, {}]", matches.size(), from, to);
        return matches;
    }

    private void saveCheckpoint(String fileUrl) {
        GdeltBackfillCheckpoint checkpoint = checkpointRepository.findById(CHECKPOINT_ID)
                .orElseGet(() -> GdeltBackfillCheckpoint.builder().id(CHECKPOINT_ID).build());
        checkpoint.setLastCompletedFile(fileUrl);
        checkpoint.setUpdatedAt(Instant.now());
        checkpointRepository.save(checkpoint);
    }

    private void logProgress(int filesDone, int filesToProcess, long rowsInserted, Instant runStart) {
        Duration elapsed = Duration.between(runStart, Instant.now());
        double avgMsPerFile = filesDone > 0 ? (double) elapsed.toMillis() / filesDone : 0;
        long remainingFiles = Math.max(0, filesToProcess - filesDone);
        long etaSeconds = (long) (avgMsPerFile * remainingFiles / 1000);
        log.info("GDELT backfill progress: {}/{} files done, {} rows inserted so far, elapsed={}s, estimated remaining={}s",
                filesDone, filesToProcess, rowsInserted, elapsed.toSeconds(), etaSeconds);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}