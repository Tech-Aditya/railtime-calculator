package com.example.railtime.model.response;

import com.example.railtime.model.BookingStatus;
import com.example.railtime.model.BookingType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Result of a general (ARP) reservation calculation.
 *
 * @param millisUntilOpening present only while {@code status == NOT_OPEN}; lets a client run a
 *                           display countdown anchored to the server's clock, not the browser's
 */
@Schema(description = "General reservation opening date/time")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReservationCalculationResponse(
        @Schema(example = "GENERAL_RESERVATION") BookingType type,
        @Schema(description = "Train origin departure date", example = "2026-12-20") LocalDate journeyDate,
        @Schema(description = "Echo of the optional boarding date", example = "2026-12-21") LocalDate boardingDate,
        @Schema(example = "2026-10-21") LocalDate bookingDate,
        @Schema(type = "string", format = "time", example = "08:00:00") LocalTime bookingTime,
        @Schema(example = "Asia/Kolkata") String timeZone,
        @Schema(example = "2026-10-21T08:00:00+05:30") OffsetDateTime bookingDateTime,
        @Schema(description = "Advance Reservation Period (days, journey date excluded)", example = "60") int arpDays,
        @Schema(example = "NOT_OPEN") BookingStatus status,
        @Schema(example = "1987200000") Long millisUntilOpening,
        @Schema(description = "Server time of the calculation (IST)", example = "2026-09-28T08:00:00+05:30")
        OffsetDateTime calculatedAt,
        DisplayValues display,
        @Schema(description = "Rule notes and caveats to show alongside the result") List<String> notes) {
}
