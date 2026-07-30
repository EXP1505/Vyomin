package com.vyomin.core_api.service;

import com.vyomin.core_api.model.intelligencegraph.Conflict;
import com.vyomin.core_api.model.intelligencegraph.Country;
import com.vyomin.core_api.repository.intelligencegraph.ConflictRepository;
import com.vyomin.core_api.repository.intelligencegraph.CountryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private static final String GDELT_LASTUPDATE_URL = "http://data.gdeltproject.org/gdeltv2/lastupdate.txt";
    private static final String CAMEO_COUNTRY_URL = "http://data.gdeltproject.org/documentation/CAMEO.country.txt";
    private static final String CAMEO_TYPE_URL = "http://data.gdeltproject.org/documentation/CAMEO.type.txt";
    private static final String CAMEO_EVENTCODES_URL =
            "http://data.gdeltproject.org/documentation/CAMEO.eventcodes.txt";

    private static final int GDELT_TIMEOUT_MS = 30_000;
    private static final DateTimeFormatter SQLDATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // Actor1CountryCode / Actor2CountryCode in the GDELT CSV use CAMEO (near-ISO3) country codes.
    private static final Map<String, String> WHITELIST_CODE_TO_NAME = Map.ofEntries(
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
            Map.entry("CAN", "Canada"), Map.entry("NGA", "Nigeria")
    );

    // Best-effort FIPS 10-4 -> whitelist name, used only to try Conflict --IN_REGION--> Country.
    // ActionGeo_CountryCode is reported in FIPS (not CAMEO), so this only ever matches when the
    // event's location happens to fall in one of our 32 whitelisted countries; anything else
    // (unmapped FIPS code, or a country outside the whitelist) just skips IN_REGION for that row.
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
            Map.entry("CA", "Canada"), Map.entry("NI", "Nigeria")
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
    private static final int MIN_COLUMNS = 58;

    private enum IngestOutcome { INGESTED, DUPLICATE }

    private final ConflictRepository conflictRepository;
    private final CountryRepository countryRepository;
    private final RestClient restClient = buildTimeoutBoundRestClient();

    private final AtomicBoolean lookupsLoaded = new AtomicBoolean(false);
    private Map<String, String> cameoCountryLookup = Map.of();
    private Map<String, String> cameoTypeLookup = Map.of();
    private Map<String, String> cameoEventCodeLookup = Map.of();

    private static RestClient buildTimeoutBoundRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(GDELT_TIMEOUT_MS);
        factory.setReadTimeout(GDELT_TIMEOUT_MS);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedRate = 900_000)
    public void scheduledIngest() {
        ingestLatestGdeltEvents();
    }

    /**
     * Downloads the latest GDELT 2.0 Events batch, keeps only events touching the actor
     * whitelist, and upserts them as Conflict nodes in AuraDB. Never throws: any failure is
     * logged and reported in the returned status so a bad cycle doesn't crash the scheduler.
     */
    @Transactional
    public Map<String, Object> ingestLatestGdeltEvents() {
        log.info("Fetching GDELT 2.0 Events...");
        loadCameoLookupsIfNeeded();

        List<String> rows;
        try {
            rows = downloadLatestEventRows();
        } catch (Exception e) {
            log.warn("Failed to download GDELT 2.0 Events batch, skipping this cycle: {}", e.getMessage(), e);
            return buildResult("error", 0, 0, 0);
        }

        int totalEvents = rows.size();
        int filteredIn = 0;
        int ingested = 0;
        int duplicates = 0;
        int failed = 0;

        for (String row : rows) {
            String[] cols = row.split("\t", -1);
            if (cols.length < MIN_COLUMNS) {
                log.warn("Skipping malformed GDELT row (only {} columns): {}", cols.length, row);
                failed++;
                continue;
            }

            String actor1Country = cols[COL_ACTOR1_COUNTRY].trim();
            String actor2Country = cols[COL_ACTOR2_COUNTRY].trim();
            if (!WHITELIST_CODE_TO_NAME.containsKey(actor1Country) && !WHITELIST_CODE_TO_NAME.containsKey(actor2Country)) {
                continue;
            }
            filteredIn++;

            try {
                IngestOutcome outcome = ingestEventRow(cols);
                if (outcome == IngestOutcome.INGESTED) {
                    ingested++;
                } else {
                    duplicates++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("Failed to parse/ingest GDELT event row, skipping. Row: {} | Error: {}", row, e.getMessage());
            }
        }

        log.info("Filtered {} events from {} total (actor whitelist)", filteredIn, totalEvents);
        log.info("Ingested {} new conflicts, {} duplicates skipped", ingested, duplicates);

        return buildResult("success", ingested, duplicates, failed);
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

    private IngestOutcome ingestEventRow(String[] cols) {
        String actor1Code = cols[COL_ACTOR1_COUNTRY].trim();
        String actor2Code = cols[COL_ACTOR2_COUNTRY].trim();
        String eventCodeRaw = cols[COL_EVENTCODE].trim();
        String sqlDateRaw = cols[COL_SQLDATE].trim();

        String dedupeKey = String.join("|", actor1Code, actor2Code, eventCodeRaw, sqlDateRaw);
        if (conflictRepository.findByGdeltEventId(dedupeKey).isPresent()) {
            return IngestOutcome.DUPLICATE;
        }

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

        Conflict conflict = new Conflict();
        conflict.setGdeltEventId(dedupeKey);
        conflict.setName(eventType + ": " + actor1Label + " - " + actor2Label);
        conflict.setDescription(actor1Label + " " + eventType + " " + actor2Label
                + (dateReported != null ? " on " + dateReported : ""));
        conflict.setStartDate(dateReported);
        conflict.setDateReported(dateReported);
        conflict.setEventType(eventType);
        conflict.setEventCode(eventCode);
        conflict.setSeverityScore(severityScore);
        conflict.setSeverity(String.valueOf(severityScore));
        conflict.setTone(tone);
        conflict.setLatitude(lat);
        conflict.setLongitude(lon);
        conflict.setActor1Name(actor1Label);
        conflict.setActor1CountryCode(actor1Code.isBlank() ? null : actor1Code);
        conflict.setActor1Type(actor1Type);
        conflict.setActor2Name(actor2Label);
        conflict.setActor2CountryCode(actor2Code.isBlank() ? null : actor2Code);
        conflict.setActor2Type(actor2Type);

        List<String> keywords = new ArrayList<>();
        keywords.add(actor1Label);
        keywords.add(actor2Label);
        keywords.add(eventType);
        conflict.setKeywords(keywords);

        Set<Country> involved = conflict.getInvolvedCountries();
        if (WHITELIST_CODE_TO_NAME.containsKey(actor1Code)) {
            involved.add(getOrCreateCountry(actor1Label));
        }
        if (WHITELIST_CODE_TO_NAME.containsKey(actor2Code)) {
            involved.add(getOrCreateCountry(actor2Label));
        }

        String actionGeoCode = cols[COL_ACTIONGEO_COUNTRYCODE].trim();
        String regionName = FIPS_TO_WHITELIST_NAME.get(actionGeoCode);
        if (regionName != null) {
            conflict.setPrimaryRegion(regionName);
            involved.add(getOrCreateCountry(regionName));
        }

        log.info("About to save conflict: dedupeKey={}, description={}, severity={}",
                dedupeKey, conflict.getDescription(), conflict.getSeverity());

        Conflict saved;
        try {
            saved = conflictRepository.save(conflict);
        } catch (Exception e) {
            log.error("AuraDB save failed for conflict [{}]: {}", conflict.getDescription(), e.getMessage(), e);
            throw e;
        }

        if (saved == null || saved.getId() == null) {
            log.error("conflictRepository.save() returned {} for dedupeKey={}",
                    saved == null ? "null" : "an entity with no id", dedupeKey);
            throw new IllegalStateException("Save did not persist a Conflict node for " + dedupeKey);
        }

        log.info("Saved conflict with ID: {}", saved.getId());
        return IngestOutcome.INGESTED;
    }

    private List<String> downloadLatestEventRows() throws IOException {
        log.info("Fetching GDELT manifest: {}", GDELT_LASTUPDATE_URL);
        String manifest = restClient.get().uri(GDELT_LASTUPDATE_URL).retrieve().body(String.class);
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

    private String unzipSingleEntry(byte[] zipBytes) throws IOException {
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

    private void loadCameoLookupsIfNeeded() {
        if (lookupsLoaded.get()) {
            return;
        }
        cameoCountryLookup = downloadCameoLookup(CAMEO_COUNTRY_URL, "CAMEO country");
        cameoTypeLookup = downloadCameoLookup(CAMEO_TYPE_URL, "CAMEO actor type");
        cameoEventCodeLookup = downloadCameoLookup(CAMEO_EVENTCODES_URL, "CAMEO event code");
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
