package com.example.railtime.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * "How far ahead can I book today?" — the booking windows as of now (IST).
 *
 * @param latestBookableJourneyDate furthest origin departure date whose general reservation is already open
 * @param nextOpening               the next journey date to open, and when
 * @param tatkal                    today's Tatkal windows (for journeys departing tomorrow)
 */
@Schema(description = "Booking windows as of now (IST)")
public record BookingWindowResponse(
        @Schema(example = "2026-09-28T21:30:00+05:30") OffsetDateTime asOf,
        @Schema(example = "2026-11-27") LocalDate latestBookableJourneyDate,
        @Schema(example = "27 November 2026") String latestBookableJourneyDisplay,
        NextOpening nextOpening,
        TatkalToday tatkal) {

    @Schema(description = "Next general-reservation opening")
    public record NextOpening(
            @Schema(example = "2026-11-28") LocalDate journeyDate,
            @Schema(example = "28 November 2026") String journeyDisplay,
            @Schema(example = "2026-09-29T08:00:00+05:30") OffsetDateTime opensAt,
            @Schema(example = "Tomorrow, 08:00 AM IST") String opensAtDisplay,
            @Schema(example = "37800000") long millisUntilOpening) {
    }

    @Schema(description = "Today's Tatkal windows")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TatkalToday(
            @Schema(example = "2026-09-29") LocalDate journeyDate,
            @Schema(example = "29 September 2026") String journeyDisplay,
            @Schema(example = "2026-09-28T10:00:00+05:30") OffsetDateTime acOpensAt,
            boolean acOpen,
            @Schema(example = "2026-09-28T11:00:00+05:30") OffsetDateTime nonAcOpensAt,
            boolean nonAcOpen,
            @Schema(example = "10:00 AM") String acTimeDisplay,
            @Schema(example = "11:00 AM") String nonAcTimeDisplay) {
    }
}
