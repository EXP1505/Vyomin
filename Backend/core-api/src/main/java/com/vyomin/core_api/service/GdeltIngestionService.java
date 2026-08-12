package com.vyomin.core_api.service;

import com.vyomin.core_api.model.intelligencegraph.Conflict;
import com.vyomin.core_api.model.intelligencegraph.Country;
import com.vyomin.core_api.repository.intelligencegraph.ConflictRepository;
import com.vyomin.core_api.repository.intelligencegraph.CountryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Ingests the GDELT 2.0 Events feed (structured, tab-delimited CSV) instead of the old free-text
 * doc/article API. Every 15 minutes (matching GDELT's own update cadence) it downloads the latest
 * events batch, keeps only events touching a hardcoded actor whitelist, and upserts them as
 * Conflict nodes in AuraDB.
 *
 * Actor nodes: GDELT 2.0 Events' Actor1/Actor2 in our whitelist are always countries, and this
 * codebase already has a Country @Node with its own repository, search endpoint, and matching
 * logic (ConflictCompanyMatcher's geographic pass keys off Country entities headquartered/involved
 * by name). Introducing a separate Actor node for the same 32 countries would duplicate that graph
 * and silently break existing country-based search/matching, so involved actors are stored as
 * Country nodes via the existing INVOLVES relationship rather than a new Actor type.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GdeltIngestionService {

    private static final int GDELT_TIMEOUT_MS = 30_000;
    private static final DateTimeFormatter SQLDATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // Actor1CountryCode / Actor2CountryCode in the GDELT CSV use CAMEO (near-ISO3) country codes.
    // Widened from an original 32-country list to ~150 so that most UN member states resolve
    // instead of silently falling through to "no cached data" on every search. Ingestion volume
    // and AuraDB free-tier node growth is bounded independently by pruneOldConflicts(), not by
    // keeping this list short.
    private static final Map<String, String> WHITELIST_CODE_TO_NAME = Map.ofEntries(
            // Original 32
            Map.entry("RUS", "Russia"), Map.entry("UKR", "Ukraine"), Map.entry("USA", "USA"),
            Map.entry("CHN", "China"), Map.entry("IND", "India"), Map.entry("ISR", "Israel"),
            Map.entry("IRN", "Iran"), Map.entry("PRK", "North Korea"), Map.entry("SYR", "Syria"),
            Map.entry("TUR", "Turkey"), Map.entry("DEU", "Germany"), Map.entry("FRA", "France"),
            Map.entry("POL", "Poland"), Map.entry("GBR", "UK"), Map.entry("SAU", "Saudi Arabia"),
            Map.entry("ARE", "UAE"), Map.entry("IRQ", "Iraq"), Map.entry("EGY", "Egypt"),
            Map.entry("YEM", "Yemen"), Map.entry("OMN", "Oman"), Map.entry("JPN", "Japan"),
            Map.entry("KOR", "South Korea"), Map.entry("PHL", "Philippines"), Map.entry("THA", "Thailand"),
            Map.entry("IDN", "Indonesia"), Map.entry("AUS", "Australia"), Map.entry("BRA", "Brazil"),
            Map.entry("ARG", "Argentina"), Map.entry("MEX", "Mexico"), Map.entry("COL", "Colombia"),
            Map.entry("CAN", "Canada"), Map.entry("NGA", "Nigeria"),
            // Europe
            Map.entry("ESP", "Spain"), Map.entry("ITA", "Italy"), Map.entry("PRT", "Portugal"),
            Map.entry("NLD", "Netherlands"), Map.entry("BEL", "Belgium"), Map.entry("CHE", "Switzerland"),
            Map.entry("AUT", "Austria"), Map.entry("SWE", "Sweden"), Map.entry("NOR", "Norway"),
            Map.entry("DNK", "Denmark"), Map.entry("FIN", "Finland"), Map.entry("GRC", "Greece"),
            Map.entry("IRL", "Ireland"), Map.entry("ROU", "Romania"), Map.entry("BGR", "Bulgaria"),
            Map.entry("HUN", "Hungary"), Map.entry("CZE", "Czech Republic"), Map.entry("SVK", "Slovakia"),
            Map.entry("HRV", "Croatia"), Map.entry("SRB", "Serbia"), Map.entry("BIH", "Bosnia and Herzegovina"),
            Map.entry("ALB", "Albania"), Map.entry("MKD", "North Macedonia"), Map.entry("SVN", "Slovenia"),
            Map.entry("EST", "Estonia"), Map.entry("LVA", "Latvia"), Map.entry("LTU", "Lithuania"),
            Map.entry("BLR", "Belarus"), Map.entry("MDA", "Moldova"), Map.entry("GEO", "Georgia"),
            Map.entry("ARM", "Armenia"), Map.entry("AZE", "Azerbaijan"), Map.entry("CYP", "Cyprus"),
            Map.entry("MLT", "Malta"), Map.entry("ISL", "Iceland"), Map.entry("LUX", "Luxembourg"),
            Map.entry("MNE", "Montenegro"),
            // Middle East / Central Asia
            Map.entry("JOR", "Jordan"), Map.entry("LBN", "Lebanon"), Map.entry("KWT", "Kuwait"),
            Map.entry("QAT", "Qatar"), Map.entry("BHR", "Bahrain"), Map.entry("AFG", "Afghanistan"),
            Map.entry("PAK", "Pakistan"), Map.entry("KAZ", "Kazakhstan"), Map.entry("UZB", "Uzbekistan"),
            Map.entry("TKM", "Turkmenistan"), Map.entry("TJK", "Tajikistan"), Map.entry("KGZ", "Kyrgyzstan"),
            Map.entry("MNG", "Mongolia"),
            // East / South / Southeast Asia
            Map.entry("TWN", "Taiwan"), Map.entry("VNM", "Vietnam"), Map.entry("MMR", "Myanmar"),
            Map.entry("MYS", "Malaysia"), Map.entry("SGP", "Singapore"), Map.entry("LAO", "Laos"),
            Map.entry("KHM", "Cambodia"), Map.entry("BGD", "Bangladesh"), Map.entry("LKA", "Sri Lanka"),
            Map.entry("NPL", "Nepal"), Map.entry("BTN", "Bhutan"), Map.entry("MDV", "Maldives"),
            // Africa
            Map.entry("ZAF", "South Africa"), Map.entry("KEN", "Kenya"), Map.entry("ETH", "Ethiopia"),
            Map.entry("SOM", "Somalia"), Map.entry("SDN", "Sudan"), Map.entry("SSD", "South Sudan"),
            Map.entry("LBY", "Libya"), Map.entry("TUN", "Tunisia"), Map.entry("DZA", "Algeria"),
            Map.entry("MAR", "Morocco"), Map.entry("GHA", "Ghana"), Map.entry("CIV", "Ivory Coast"),
            Map.entry("SEN", "Senegal"), Map.entry("MLI", "Mali"), Map.entry("NER", "Niger"),
            Map.entry("TCD", "Chad"), Map.entry("CMR", "Cameroon"), Map.entry("COD", "DR Congo"),
            Map.entry("COG", "Congo"), Map.entry("UGA", "Uganda"), Map.entry("TZA", "Tanzania"),
            Map.entry("RWA", "Rwanda"), Map.entry("BDI", "Burundi"), Map.entry("ZWE", "Zimbabwe"),
            Map.entry("ZMB", "Zambia"), Map.entry("MOZ", "Mozambique"), Map.entry("AGO", "Angola"),
            Map.entry("NAM", "Namibia"), Map.entry("BWA", "Botswana"), Map.entry("SLE", "Sierra Leone"),
            Map.entry("LBR", "Liberia"), Map.entry("GIN", "Guinea"), Map.entry("BFA", "Burkina Faso"),
            Map.entry("MRT", "Mauritania"), Map.entry("ERI", "Eritrea"), Map.entry("DJI", "Djibouti"),
            Map.entry("GAB", "Gabon"),
            // Americas
            Map.entry("CHL", "Chile"), Map.entry("PER", "Peru"), Map.entry("VEN", "Venezuela"),
            Map.entry("ECU", "Ecuador"), Map.entry("BOL", "Bolivia"), Map.entry("PRY", "Paraguay"),
            Map.entry("URY", "Uruguay"), Map.entry("CUB", "Cuba"), Map.entry("HTI", "Haiti"),
            Map.entry("DOM", "Dominican Republic"), Map.entry("GTM", "Guatemala"), Map.entry("HND", "Honduras"),
            Map.entry("NIC", "Nicaragua"), Map.entry("CRI", "Costa Rica"), Map.entry("PAN", "Panama"),
            Map.entry("JAM", "Jamaica"),
            // Oceania
            Map.entry("NZL", "New Zealand"), Map.entry("PNG", "Papua New Guinea"), Map.entry("FJI", "Fiji")
    );

    // Best-effort FIPS 10-4 -> whitelist name, used only to try Conflict --IN_REGION--> Country.
    // ActionGeo_CountryCode is reported in FIPS (not CAMEO), so this only ever matches when the
    // event's location happens to fall in one of our whitelisted countries; anything else
    // (unmapped FIPS code, or a country outside the whitelist) just skips IN_REGION for that row.
    // Not every whitelisted country has a confirmed FIPS entry here - unmapped ones degrade
    // gracefully (primaryRegion just stays unset) rather than risk a wrong code-to-country guess.
    private static final Map<String, String> FIPS_TO_WHITELIST_NAME = Map.ofEntries(
            Map.entry("RS", "Russia"), Map.entry("UP", "Ukraine"), Map.entry("US", "USA"),
            Map.entry("CH", "China"), Map.entry("IN", "India"), Map.entry("IS", "Israel"),
            Map.entry("IR", "Iran"), Map.entry("KN", "North Korea"), Map.entry("SY", "Syria"),
            Map.entry("TU", "Turkey"), Map.entry("GM", "Germany"), Map.entry("FR", "France"),
            Map.entry("PL", "Poland"), Map.entry("UK", "UK"), Map.entry("SA", "Saudi Arabia"),
            Map.entry("AE", "UAE"), Map.entry("IZ", "Iraq"), Map.entry("EG", "Egypt"),
            Map.entry("YM", "Yemen"), Map.entry("MU", "Oman"), Map.entry("JA", "Japan"),
            Map.entry("KS", "South Korea"), Map.entry("RP", "Philippines"), Map.entry("TH", "Thailand"),
            Map.entry("ID", "Indonesia"), Map.entry("AS", "Australia"), Map.entry("BR", "Brazil"),
            Map.entry("AR", "Argentina"), Map.entry("MX", "Mexico"), Map.entry("CO", "Colombia"),
            Map.entry("CA", "Canada"), Map.entry("NI", "Nigeria"),
            Map.entry("SP", "Spain"), Map.entry("IT", "Italy"), Map.entry("PO", "Portugal"),
            Map.entry("NL", "Netherlands"), Map.entry("BE", "Belgium"), Map.entry("SZ", "Switzerland"),
            Map.entry("AU", "Austria"), Map.entry("SW", "Sweden"), Map.entry("NO", "Norway"),
            Map.entry("DA", "Denmark"), Map.entry("FI", "Finland"), Map.entry("GR", "Greece"),
            Map.entry("EI", "Ireland"), Map.entry("RO", "Romania"), Map.entry("BU", "Bulgaria"),
            Map.entry("HU", "Hungary"), Map.entry("EZ", "Czech Republic"), Map.entry("LO", "Slovakia"),
            Map.entry("HR", "Croatia"), Map.entry("RI", "Serbia"), Map.entry("BK", "Bosnia and Herzegovina"),
            Map.entry("AL", "Albania"), Map.entry("MK", "North Macedonia"), Map.entry("SI", "Slovenia"),
            Map.entry("EN", "Estonia"), Map.entry("LG", "Latvia"), Map.entry("LH", "Lithuania"),
            Map.entry("BO", "Belarus"), Map.entry("MD", "Moldova"), Map.entry("GG", "Georgia"),
            Map.entry("AM", "Armenia"), Map.entry("AJ", "Azerbaijan"), Map.entry("CY", "Cyprus"),
            Map.entry("MT", "Malta"), Map.entry("IC", "Iceland"), Map.entry("LU", "Luxembourg"),
            Map.entry("MJ", "Montenegro"),
            Map.entry("JO", "Jordan"), Map.entry("LE", "Lebanon"), Map.entry("KU", "Kuwait"),
            Map.entry("QA", "Qatar"), Map.entry("BA", "Bahrain"), Map.entry("AF", "Afghanistan"),
            Map.entry("PK", "Pakistan"), Map.entry("KZ", "Kazakhstan"), Map.entry("UZ", "Uzbekistan"),
            Map.entry("TX", "Turkmenistan"), Map.entry("TI", "Tajikistan"), Map.entry("KG", "Kyrgyzstan"),
            Map.entry("MG", "Mongolia"),
            Map.entry("TW", "Taiwan"), Map.entry("VM", "Vietnam"), Map.entry("BM", "Myanmar"),
            Map.entry("MY", "Malaysia"), Map.entry("SN", "Singapore"), Map.entry("LA", "Laos"),
            Map.entry("CB", "Cambodia"), Map.entry("BG", "Bangladesh"), Map.entry("CE", "Sri Lanka"),
            Map.entry("NP", "Nepal"), Map.entry("BT", "Bhutan"), Map.entry("MV", "Maldives"),
            Map.entry("SF", "South Africa"), Map.entry("KE", "Kenya"), Map.entry("ET", "Ethiopia"),
            Map.entry("SO", "Somalia"), Map.entry("SU", "Sudan"), Map.entry("OD", "South Sudan"),
            Map.entry("LY", "Libya"), Map.entry("TS", "Tunisia"), Map.entry("AG", "Algeria"),
            Map.entry("MO", "Morocco"), Map.entry("GH", "Ghana"), Map.entry("IV", "Ivory Coast"),
            Map.entry("SG", "Senegal"), Map.entry("ML", "Mali"), Map.entry("NG", "Niger"),
            Map.entry("CD", "Chad"), Map.entry("CM", "Cameroon"), Map.entry("CG", "DR Congo"),
            Map.entry("CF", "Congo"), Map.entry("UG", "Uganda"), Map.entry("TZ", "Tanzania"),
            Map.entry("RW", "Rwanda"), Map.entry("BY", "Burundi"), Map.entry("ZI", "Zimbabwe"),
            Map.entry("ZA", "Zambia"), Map.entry("MZ", "Mozambique"), Map.entry("AO", "Angola"),
            Map.entry("WA", "Namibia"), Map.entry("BC", "Botswana"), Map.entry("SL", "Sierra Leone"),
            Map.entry("LI", "Liberia"), Map.entry("GV", "Guinea"), Map.entry("UV", "Burkina Faso"),
            Map.entry("MR", "Mauritania"), Map.entry("ER", "Eritrea"), Map.entry("DJ", "Djibouti"),
            Map.entry("GB", "Gabon"),
            Map.entry("CI", "Chile"), Map.entry("PE", "Peru"), Map.entry("VE", "Venezuela"),
            Map.entry("EC", "Ecuador"), Map.entry("BL", "Bolivia"), Map.entry("PA", "Paraguay"),
            Map.entry("UY", "Uruguay"), Map.entry("CU", "Cuba"), Map.entry("HA", "Haiti"),
            Map.entry("DR", "Dominican Republic"), Map.entry("GT", "Guatemala"), Map.entry("HO", "Honduras"),
            Map.entry("NU", "Nicaragua"), Map.entry("CS", "Costa Rica"), Map.entry("PM", "Panama"),
            Map.entry("JM", "Jamaica"),
            Map.entry("NZ", "New Zealand"), Map.entry("PP", "Papua New Guinea"), Map.entry("FJ", "Fiji")
    );

    // CAMEO quad-class root event codes (01-20) - stable, documented taxonomy; used as the
    // primary eventType label since it matches the examples given (19=Fight, 18=Assault, 13=Threaten).
    private static final Map<String, String> ROOT_EVENT_LABELS = Map.ofEntries(
            Map.entry("01", "Make Statement"), Map.entry("02", "Appeal"),
            Map.entry("03", "Express Intent to Cooperate"), Map.entry("04", "Consult"),
            Map.entry("05", "Engage in Diplomatic Cooperation"), Map.entry("06", "Engage in Material Cooperation"),
            Map.entry("07", "Provide Aid"), Map.entry("08", "Yield"),
            Map.entry("09", "Investigate"), Map.entry("10", "Demand"),
            Map.entry("11", "Disapprove"), Map.entry("12", "Reject"),
            Map.entry("13", "Threaten"), Map.entry("14", "Protest"),
            Map.entry("15", "Exhibit Force Posture"), Map.entry("16", "Reduce Relations"),
            Map.entry("17", "Coerce"), Map.entry("18", "Assault"),
            Map.entry("19", "Fight"), Map.entry("20", "Use Unconventional Mass Violence")
    );

    // Fallback labels for common Actor Type1 codes, used only if the downloaded CAMEO.type.txt
    // lookup is unavailable (download failure) or missing a code.
    private static final Map<String, String> FALLBACK_TYPE_LABELS = Map.ofEntries(
            Map.entry("GOV", "Government"), Map.entry("MIL", "Military"), Map.entry("REB", "Rebel"),
            Map.entry("OPP", "Opposition"), Map.entry("BUS", "Business"), Map.entry("JUD", "Judicial"),
            Map.entry("SPY", "Intelligence"), Map.entry("COP", "Police"), Map.entry("MED", "Media"),
            Map.entry("EDU", "Education"), Map.entry("ENV", "Environmental"), Map.entry("CVL", "Civilian"),
            Map.entry("CRM", "Criminal"), Map.entry("IGO", "Intergovernmental Organization"),
            Map.entry("NGO", "Non-Governmental Organization"), Map.entry("UAF", "Unaligned Armed Forces")
    );

    // Column indices in the headerless GDELT 2.0 Events CSV (61 tab-delimited columns).
    private static final int COL_GLOBALEVENTID = 0;
    private static final int COL_SQLDATE = 1;
    private static final int COL_ACTOR1_NAME = 6;
    private static final int COL_ACTOR1_COUNTRY = 7;
    private static final int COL_ACTOR1_TYPE1 = 12;
    private static final int COL_ACTOR2_NAME = 16;
    private static final int COL_ACTOR2_COUNTRY = 17;
    private static final int COL_ACTOR2_TYPE1 = 22;
    private static final int COL_EVENTCODE = 26;
    private static final int COL_EVENT_ROOTCODE = 28;
    private static final int COL_GOLDSTEIN = 30;
    private static final int COL_AVGTONE = 34;
    private static final int COL_ACTIONGEO_COUNTRYCODE = 53;
    private static final int COL_ACTIONGEO_LAT = 56;
    private static final int COL_ACTIONGEO_LONG = 57;
    private static final int COL_SOURCEURL = 60;
    // Package-private (not private): GdeltHistoricalBackfillService reuses this to validate rows
    // from historical batch files the same way live ingestion does.
    static final int MIN_COLUMNS = 61;

    private final ConflictRepository conflictRepository;
    private final CountryRepository countryRepository;
    private final RestClient restClient = buildTimeoutBoundRestClient();

    @Value("${gdelt.manifest.url}")
    private String gdeltManifestUrl;

    @Value("${gdelt.cameo.country.url}")
    private String cameoCountryUrl;

    @Value("${gdelt.cameo.type.url}")
    private String cameoTypeUrl;

    @Value("${gdelt.cameo.event.url}")
    private String cameoEventCodesUrl;

    @Value("${gdelt.conflict.retention-days}")
    private int conflictRetentionDays;

    private static final int SAVE_BATCH_SIZE = 250;
    // GDELT's own feed only updates every 15 minutes, so an on-demand fetch that lands within
    // this window of the last completed run has nothing new to find - skip re-downloading and
    // re-walking the whole batch just to discover that.
    private static final Duration MIN_RE_INGEST_INTERVAL = Duration.ofMinutes(10);

    private final AtomicBoolean lookupsLoaded = new AtomicBoolean(false);
    // Guards against the scheduled 15-minute ingest and an on-demand search-triggered ingest
    // overlapping: without this, a search landing mid-cycle used to queue a second full
    // download+row-by-row-save pass behind (or alongside) the one already running, each row
    // taking a network round trip to AuraDB, which is what made searches hang for minutes.
    private final AtomicBoolean ingestionInProgress = new AtomicBoolean(false);
    private volatile Instant lastCompletedAt;
    private Map<String, String> cameoCountryLookup = Map.of();
    private Map<String, String> cameoTypeLookup = Map.of();
    private Map<String, String> cameoEventCodeLookup = Map.of();

    private static RestClient buildTimeoutBoundRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(GDELT_TIMEOUT_MS);
        factory.setReadTimeout(GDELT_TIMEOUT_MS);
        return RestClient.builder().requestFactory(factory).build();
    }

    // Staggered against IntelligenceIngestionService's two scheduled jobs (ingestGdeltNews,
    // ingestOpenNetworkData) so all three don't fire simultaneously on every app startup/restart -
    // that pile-up of concurrent Neo4j load is what exhausted the connection pool and made
    // interactive search requests time out waiting for a free connection.
    @Scheduled(fixedRate = 900_000, initialDelay = 0)
    public void scheduledIngest() {
        ingestLatestGdeltEvents();
    }

    // Runs once a day - conflict volume changes slowly enough that hourly pruning would just be
    // wasted AuraDB round trips. Offset from scheduledIngest's initialDelay=0 so the two don't
    // compete for the same connection pool slot on every app restart.
    private static final long PRUNE_INTERVAL_MS = 24 * 60 * 60 * 1000L;

    @Scheduled(fixedRate = PRUNE_INTERVAL_MS, initialDelay = PRUNE_INTERVAL_MS)
    public void scheduledPruneOldConflicts() {
        pruneOldConflicts();
    }

    /**
     * Deletes Conflict nodes older than gdelt.conflict.retention-days. Ingestion runs forever
     * every 15 minutes with no upper bound on its own, so without this the Conflict node count
     * (and its INVOLVES relationships) grows monotonically and eventually exceeds AuraDB free
     * tier's 200k-node cap - this is what actually keeps the free tier usable long-term, not the
     * size of the country whitelist.
     */
    public long pruneOldConflicts() {
        LocalDate cutoff = LocalDate.now().minusDays(conflictRetentionDays);
        long deleted = conflictRepository.deleteByDateReportedBefore(cutoff);
        if (deleted > 0) {
            log.info("Pruned {} Conflict nodes older than {} ({} day retention)", deleted, cutoff, conflictRetentionDays);
        }
        return deleted;
    }

    /**
     * Downloads the latest GDELT 2.0 Events batch, keeps only events touching the actor
     * whitelist, and upserts them as Conflict nodes in AuraDB. Never throws: any failure is
     * logged and reported in the returned status so a bad cycle doesn't crash the scheduler.
     */
    @Transactional
    public Map<String, Object> ingestLatestGdeltEvents() {
        Instant lastRun = lastCompletedAt;
        if (lastRun != null && Duration.between(lastRun, Instant.now()).compareTo(MIN_RE_INGEST_INTERVAL) < 0) {
            log.info("Skipping GDELT ingest: last completed run was less than {} ago", MIN_RE_INGEST_INTERVAL);
            return buildResult("skipped-recent", 0, 0, 0);
        }
        if (!ingestionInProgress.compareAndSet(false, true)) {
            log.info("Skipping GDELT ingest: a run is already in progress");
            return buildResult("skipped-in-progress", 0, 0, 0);
        }

        try {
            log.info("Fetching GDELT 2.0 Events...");
            ensureCameoLookupsLoaded();

            List<String> rows;
            try {
                rows = downloadLatestEventRows();
            } catch (Exception e) {
                log.warn("Failed to download GDELT 2.0 Events batch, skipping this cycle: {}", e.getMessage(), e);
                return buildResult("error", 0, 0, 0);
            }

            int totalEvents = rows.size();
            int failed = 0;

            // Pass 1: cheap in-memory whitelist filter, no DB access yet.
            List<String[]> whitelisted = new ArrayList<>();
            for (String row : rows) {
                String[] cols = row.split("\t", -1);
                if (cols.length < MIN_COLUMNS) {
                    log.warn("Skipping malformed GDELT row (only {} columns): {}", cols.length, row);
                    failed++;
                    continue;
                }
                if (!isWhitelisted(cols)) {
                    continue;
                }
                whitelisted.add(cols);
            }
            int filteredIn = whitelisted.size();
            log.info("Filtered {} events from {} total (actor whitelist)", filteredIn, totalEvents);

            // Pass 2: one bulk existence check instead of a findByGdeltEventId() round trip per
            // row - the per-row version was the actual bottleneck even after batching saves,
            // since AuraDB network latency applies per query regardless of whether it's a read
            // or a write, and a batch commonly has hundreds of whitelist-matching rows.
            List<String> dedupeKeys = whitelisted.stream().map(this::buildDedupeKey).toList();
            Set<String> existingIds = dedupeKeys.isEmpty()
                    ? Set.of()
                    : conflictRepository.findByGdeltEventIdIn(dedupeKeys).stream()
                            .map(Conflict::getGdeltEventId)
                            .collect(java.util.stream.Collectors.toSet());

            // Pre-warm with every whitelisted country in one round trip so the loop below almost
            // never has to fall through to a per-name lookup.
            Map<String, Country> countryCache = new java.util.HashMap<>();
            countryRepository.findByNameIn(WHITELIST_CODE_TO_NAME.values())
                    .forEach(c -> countryCache.put(c.getName(), c));

            int duplicates = 0;
            List<Conflict> pendingBatch = new ArrayList<>(SAVE_BATCH_SIZE);

            for (int i = 0; i < whitelisted.size(); i++) {
                String[] cols = whitelisted.get(i);
                String dedupeKey = dedupeKeys.get(i);
                if (existingIds.contains(dedupeKey)) {
                    duplicates++;
                    continue;
                }
                try {
                    pendingBatch.add(parseConflictFromRow(cols, dedupeKey, countryCache));
                } catch (Exception e) {
                    failed++;
                    log.error("Failed to parse conflict from row (eventId={}) | Exception class: {} | Message: {}",
                            dedupeKey, e.getClass().getName(), e.getMessage(), e);
                }

                if (pendingBatch.size() >= SAVE_BATCH_SIZE) {
                    failed += flushBatch(pendingBatch);
                }
            }
            failed += flushBatch(pendingBatch);

            int ingested = filteredIn - duplicates - failed;
            log.info("Ingestion complete: success={}, failed={}, duplicates={}", ingested, failed, duplicates);

            lastCompletedAt = Instant.now();
            return buildResult("success", ingested, duplicates, failed);
        } finally {
            ingestionInProgress.set(false);
        }
    }

    /**
     * Saves a batch of parsed conflicts in one round trip instead of one save() per row - with
     * AuraDB's network latency, one-at-a-time saves are what turned a several-thousand-row batch
     * into a many-minutes-long loop. Clears the batch list regardless of outcome so the caller
     * can keep accumulating. Returns the number of rows that failed to save.
     */
    private int flushBatch(List<Conflict> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        int size = batch.size();
        try {
            conflictRepository.saveAll(batch);
            log.info("Saved batch of {} conflicts", size);
            return 0;
        } catch (Exception e) {
            log.error("Failed to save batch of {} conflicts: {}", size, e.getMessage(), e);
            return size;
        } finally {
            batch.clear();
        }
    }

    /**
     * GDELT 2.0 Events is a bulk actor-whitelist feed, not a keyword search API, so there's no
     * way to query it for a specific term the way the old doc API allowed. Kept for
     * AsyncDataFetchService's on-demand "no cached results" path: it just triggers the same
     * latest-batch ingestion, which will pick up anything matching the whitelist regardless of
     * the search text.
     */
    public Map<String, Object> fetchAndIngestForQuery(String query) {
        log.info("fetchAndIngestForQuery called with query='{}' (GDELT 2.0 Events has no keyword search; " +
                "pulling latest whitelist-filtered batch instead)", query);
        return ingestLatestGdeltEvents();
    }

    /**
     * GDELT's GLOBALEVENTID is the only field guaranteed unique per row. An earlier version of
     * this key was Actor1Code|Actor2Code|EventCode|SQLDate, which collapses every article that
     * shares an actor pair/event/day onto the same key - GDELT routinely has many distinct
     * articles about the same actor pair on the same day, so that key was never actually unique.
     * With no DB-level uniqueness constraint on gdeltEventId, that let duplicate Conflict nodes
     * accumulate, which then made findByGdeltEventId (an Optional<Conflict>-returning query,
     * which requires 0-or-1 matches) throw IncorrectResultSizeDataAccessException on every future
     * row whose coarse key collided with an existing duplicate pair.
     */
    // Package-private (not private): GdeltHistoricalBackfillService reuses this instead of
    // re-deriving actor whitelist membership itself.
    boolean isWhitelisted(String[] cols) {
        String actor1Country = cols[COL_ACTOR1_COUNTRY].trim();
        String actor2Country = cols[COL_ACTOR2_COUNTRY].trim();
        return WHITELIST_CODE_TO_NAME.containsKey(actor1Country) || WHITELIST_CODE_TO_NAME.containsKey(actor2Country);
    }

    // Package-private (not private): shared with GdeltHistoricalBackfillService so the dedupe key
    // format is defined in exactly one place. See the javadoc on the original private version's
    // history for why GLOBALEVENTID (not a composite actor/event/date key) is what's unique here.
    String buildDedupeKey(String[] cols) {
        return cols[COL_GLOBALEVENTID].trim();
    }

    /**
     * Parses a whitelisted GDELT row into a source-agnostic DTO - no Neo4j/Country access here,
     * just CAMEO/actor/date/severity field extraction, so this same method serves both the live
     * Conflict path (via toConflict below) and the historical Postgres backfill path directly.
     * Package-private so GdeltHistoricalBackfillService can call it.
     */
    ParsedConflictEvent parseRow(String[] cols, String dedupeKey) {
        String actor1Code = cols[COL_ACTOR1_COUNTRY].trim();
        String actor2Code = cols[COL_ACTOR2_COUNTRY].trim();
        String eventCodeRaw = cols[COL_EVENTCODE].trim();
        String sqlDateRaw = cols[COL_SQLDATE].trim();

        String actor1Name = resolveCountryName(actor1Code);
        String actor2Name = resolveCountryName(actor2Code);
        String actor1Label = actor1Name != null ? actor1Name
                : (cols[COL_ACTOR1_NAME].isBlank() ? actor1Code : cols[COL_ACTOR1_NAME]);
        String actor2Label = actor2Name != null ? actor2Name
                : (cols[COL_ACTOR2_NAME].isBlank() ? actor2Code : cols[COL_ACTOR2_NAME]);
        String actor1Type = resolveActorType(cols[COL_ACTOR1_TYPE1].trim());
        String actor2Type = resolveActorType(cols[COL_ACTOR2_TYPE1].trim());

        String rootCode = cols[COL_EVENT_ROOTCODE].trim();
        String eventType = ROOT_EVENT_LABELS.getOrDefault(rootCode,
                cameoEventCodeLookup.getOrDefault(eventCodeRaw, "Other"));
        Integer eventCode = parseIntSafe(rootCode);

        double goldstein = parseDoubleSafe(cols[COL_GOLDSTEIN], 0.0);
        int severityScore = mapGoldsteinToSeverity(goldstein);
        Double tone = parseDoubleOrNull(cols[COL_AVGTONE]);
        Double lat = parseDoubleOrNull(cols[COL_ACTIONGEO_LAT]);
        Double lon = parseDoubleOrNull(cols[COL_ACTIONGEO_LONG]);
        LocalDate dateReported = parseSqlDate(sqlDateRaw);

        String name = eventType + ": " + actor1Label + " - " + actor2Label;
        String description = actor1Label + " " + eventType + " " + actor2Label
                + (dateReported != null ? " on " + dateReported : "");

        String sourceUrl = cols[COL_SOURCEURL].trim();

        Set<String> involvedCountryNames = new java.util.LinkedHashSet<>();
        if (WHITELIST_CODE_TO_NAME.containsKey(actor1Code)) {
            involvedCountryNames.add(actor1Label);
        }
        if (WHITELIST_CODE_TO_NAME.containsKey(actor2Code)) {
            involvedCountryNames.add(actor2Label);
        }

        String actionGeoCode = cols[COL_ACTIONGEO_COUNTRYCODE].trim();
        String regionName = FIPS_TO_WHITELIST_NAME.get(actionGeoCode);
        if (regionName != null) {
            involvedCountryNames.add(regionName);
        }

        return new ParsedConflictEvent(
                dedupeKey,
                dateReported,
                eventType,
                eventCode,
                name,
                description,
                severityScore,
                goldstein,
                tone,
                lat,
                lon,
                actor1Label,
                actor1Code.isBlank() ? null : actor1Code,
                actor1Type,
                actor2Label,
                actor2Code.isBlank() ? null : actor2Code,
                actor2Type,
                regionName,
                sourceUrl.isBlank() ? null : sourceUrl,
                List.of(actor1Label, actor2Label, eventType),
                involvedCountryNames
        );
    }

    /** Maps a parsed event onto a Conflict node, resolving/creating its INVOLVES Country relationships. */
    private Conflict toConflict(ParsedConflictEvent event, Map<String, Country> countryCache) {
        Conflict conflict = new Conflict();
        conflict.setGdeltEventId(event.gdeltEventId());
        conflict.setName(event.name());
        conflict.setDescription(event.description());
        conflict.setStartDate(event.eventDate());
        conflict.setDateReported(event.eventDate());
        conflict.setEventType(event.eventType());
        conflict.setEventCode(event.eventCode());
        conflict.setSeverityScore(event.severityScore());
        conflict.setSeverity(String.valueOf(event.severityScore()));
        conflict.setTone(event.tone());
        conflict.setLatitude(event.latitude());
        conflict.setLongitude(event.longitude());
        conflict.setActor1Name(event.actor1Name());
        conflict.setActor1CountryCode(event.actor1CountryCode());
        conflict.setActor1Type(event.actor1Type());
        conflict.setActor2Name(event.actor2Name());
        conflict.setActor2CountryCode(event.actor2CountryCode());
        conflict.setActor2Type(event.actor2Type());
        conflict.setSourceUrl(event.sourceUrl());
        conflict.setKeywords(new ArrayList<>(event.keywords()));
        conflict.setPrimaryRegion(event.primaryRegion());

        Set<Country> involved = conflict.getInvolvedCountries();
        for (String countryName : event.involvedCountryNames()) {
            Country country = getOrCreateCountryCached(countryName, countryCache);
            if (country != null) {
                involved.add(country);
            }
        }

        return conflict;
    }

    private Conflict parseConflictFromRow(String[] cols, String dedupeKey, Map<String, Country> countryCache) {
        return toConflict(parseRow(cols, dedupeKey), countryCache);
    }

    private List<String> downloadLatestEventRows() throws IOException {
        log.info("Fetching GDELT manifest: {}", gdeltManifestUrl);
        String manifest = restClient.get().uri(gdeltManifestUrl).retrieve().body(String.class);
        if (manifest == null || manifest.isBlank()) {
            throw new IOException("GDELT lastupdate.txt manifest was empty");
        }

        String zipUrl = manifest.lines()
                .filter(line -> line.contains(".export.CSV.zip"))
                .map(line -> line.trim().split("\\s+"))
                .filter(parts -> parts.length >= 3)
                .map(parts -> parts[2])
                .findFirst()
                .orElseThrow(() -> new IOException("Could not find an export.CSV.zip entry in GDELT manifest"));

        log.info("Downloading GDELT events batch: {}", zipUrl);
        byte[] zipBytes = restClient.get().uri(zipUrl).retrieve().body(byte[].class);
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IOException("Downloaded GDELT events zip was empty");
        }
        log.info("GDELT response received: {} bytes", zipBytes.length);

        log.info("Parsing GDELT events CSV...");
        String csv = unzipSingleEntry(zipBytes);
        List<String> rows = csv.lines().filter(line -> !line.isBlank()).toList();
        log.info("Ingesting articles... ({} raw event rows in batch)", rows.size());
        return rows;
    }

    // Package-private (not private): GdeltHistoricalBackfillService reuses this to unzip each
    // historical batch file - same single-CSV-entry zip format as the live manifest download.
    String unzipSingleEntry(byte[] zipBytes) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry = zis.getNextEntry();
            if (entry == null) {
                throw new IOException("GDELT zip archive had no entries");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            zis.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    // Package-private (not private): GdeltHistoricalBackfillService calls this before parsing so
    // its eventType/actorType labels resolve through the same CAMEO lookups as live ingestion,
    // instead of only ever falling back to the smaller built-in label maps.
    void ensureCameoLookupsLoaded() {
        if (lookupsLoaded.get()) {
            return;
        }
        cameoCountryLookup = downloadCameoLookup(cameoCountryUrl, "CAMEO country");
        cameoTypeLookup = downloadCameoLookup(cameoTypeUrl, "CAMEO actor type");
        cameoEventCodeLookup = downloadCameoLookup(cameoEventCodesUrl, "CAMEO event code");
        lookupsLoaded.set(true);
    }

    private Map<String, String> downloadCameoLookup(String url, String label) {
        try {
            String body = restClient.get().uri(url).retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                log.warn("{} lookup file was empty, falling back to built-in labels", label);
                return Map.of();
            }
            Map<String, String> lookup = new LinkedHashMap<>();
            body.lines().skip(1).forEach(line -> {
                String[] parts = line.split("\t", 2);
                if (parts.length == 2 && !parts[0].isBlank()) {
                    lookup.put(parts[0].trim(), parts[1].trim());
                }
            });
            log.info("Loaded {} entries from {} lookup", lookup.size(), label);
            return lookup;
        } catch (Exception e) {
            log.warn("Failed to download {} lookup, falling back to built-in labels: {}", label, e.getMessage());
            return Map.of();
        }
    }

    private String resolveCountryName(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String whitelisted = WHITELIST_CODE_TO_NAME.get(code);
        if (whitelisted != null) {
            return whitelisted;
        }
        return cameoCountryLookup.get(code);
    }

    private String resolveActorType(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String fromLookup = cameoTypeLookup.get(code);
        if (fromLookup != null) {
            return fromLookup;
        }
        return FALLBACK_TYPE_LABELS.getOrDefault(code, code);
    }

    private int mapGoldsteinToSeverity(double goldstein) {
        double clamped = Math.max(-10.0, Math.min(10.0, goldstein));
        return (int) Math.round((clamped + 10.0) / 20.0 * 100.0);
    }

    private double parseDoubleSafe(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Double parseDoubleOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseIntSafe(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate parseSqlDate(String raw) {
        if (raw == null || raw.length() < 8) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, 8), SQLDATE_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * There are only ~32 possible whitelisted country names, but a batch has hundreds of
     * whitelist-matching rows, each mentioning one - resolving country-by-name fresh from Neo4j
     * per row (instead of caching per ingestion run) was another per-row round trip alongside the
     * dedupe check and the save, and just as slow.
     */
    private Country getOrCreateCountryCached(String name, Map<String, Country> cache) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Country cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        Country country = getOrCreateCountry(name);
        cache.put(name, country);
        return country;
    }

    private Country getOrCreateCountry(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return countryRepository.findByName(name).orElseGet(() -> {
            Country country = new Country();
            country.setName(name);
            country.setRegion("Unknown");
            return countryRepository.save(country);
        });
    }

    private Map<String, Object> buildResult(String status, int ingested, int duplicates, int failed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("ingested", ingested);
        result.put("duplicates", duplicates);
        result.put("failed", failed);
        result.put("timestamp", Instant.now().toString());
        return result;
    }
}
