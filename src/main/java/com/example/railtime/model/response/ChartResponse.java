package com.example.railtime.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Estimated reservation chart times for a departure.
 *
 * @param millisUntilFirstChart present only while the first chart is still pending
 */
@Schema(description = "Reservation chart preparation estimate")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartResponse(
        @Schema(example = "2026-12-20T10:30:00+05:30") OffsetDateTime departureDateTime,
        @Schema(example = "2026-12-19T20:00:00+05:30") OffsetDateTime firstChartAt,
        @Schema(example = "2026-12-20T10:00:00+05:30") OffsetDateTime finalChartAt,
        @Schema(example = "PREVIOUS_EVENING") ChartRule rule,
        @Schema(example = "Departures between 05:01 AM and 02:00 PM: first chart by 08:00 PM the previous day") String ruleDescription,
        @Schema(example = "PENDING") ChartStatus status,
        @Schema(example = "7200000") Long millisUntilFirstChart,
        Display display,
        List<String> notes) {

    public enum ChartRule { PREVIOUS_EVENING, HOURS_BEFORE_DEPARTURE }

    public enum ChartStatus {
        /** First chart not yet prepared. */
        PENDING,
        /** First chart prepared; final chart still to come. */
        FIRST_CHART_PREPARED,
        /** Final chart prepared; the train has not departed yet. */
        FINAL_CHART_PREPARED,
        /** Scheduled departure has passed. */
        DEPARTED
    }

    @Schema(description = "Pre-formatted display strings (English, IST)")
    public record Display(
            @Schema(example = "19 December 2026") String firstChartDate,
            @Schema(example = "Saturday") String firstChartWeekday,
            @Schema(example = "08:00 PM IST") String firstChartTime,
            @Schema(example = "10:00 AM IST") String finalChartTime,
            @Schema(example = "20 December 2026, 10:30 AM IST") String departure) {
    }
}
