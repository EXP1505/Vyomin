package com.vyomin.core_api.dto;

import com.vyomin.core_api.dto.EventStudyDtos.WindowSummary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class EventStudySweepDtos {

    /**
     * countryPairs is a list of [actor1, actor2] pairs. If null/empty, the service falls back to
     * a hardcoded default list of real-world pairs (see EventStudyService.DEFAULT_COUNTRY_PAIRS).
     */
    public record EventStudySweepRequest(
            List<String> eventTypes,
            List<List<String>> countryPairs,
            LocalDate dateFrom,
            LocalDate dateTo,
            List<String> basket,
            List<Integer> windows
    ) {
    }

    /**
     * summary is the full existing per-window WindowSummary array for this combo, unfiltered.
     * survivesCorrection is true iff bestPValue < correctedThreshold (see EventStudySweepResponse)
     * - i.e. still significant after Bonferroni-correcting for every combination run in this sweep.
     */
    public record SweepResult(String eventType, String actor1, String actor2, Integer bestWindow,
                               BigDecimal bestPValue, boolean survivesCorrection, List<WindowSummary> summary) {
    }

    /**
     * testableCombinationsCount excludes any combination where any requested window came back
     * NO_DATA or INVALID_SATURATED - results contains only the testable ones, ranked by
     * bestPValue ascending (most significant first).
     *
     * totalTestsForCorrection/correctedThreshold expose the Bonferroni correction's inputs so the
     * UI can show its work: correctedThreshold = 0.05 / totalTestsForCorrection, where N is
     * totalCombinationsRun (every hypothesis this sweep attempted, not just the testable subset).
     */
    public record EventStudySweepResponse(int totalCombinationsRun, int testableCombinationsCount,
                                           int totalTestsForCorrection, BigDecimal correctedThreshold,
                                           List<SweepResult> results) {
    }
}
