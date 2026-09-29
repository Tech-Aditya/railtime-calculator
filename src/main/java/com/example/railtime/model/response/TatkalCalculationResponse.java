package com.example.railtime.model.response;

import com.example.railtime.model.BookingStatus;
import com.example.railtime.model.BookingType;
import com.example.railtime.model.TatkalCategory;
import com.example.railtime.model.TravelClass;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Result of a Tatkal calculation.
 *
 * <p>For a class that is not listed among the standard IRCTC Tatkal classes (1A), every
 * date/time field is omitted, {@code status} is {@code NOT_APPLICABLE} and {@code message}
 * explains why — the API never returns a made-up opening time.
 */
@Schema(description = "Tatkal opening date/time, or a business-rule explanation when no standard time applies")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TatkalCalculationResponse(
        @Schema(example = "TATKAL") BookingType type,
        @Schema(example = "2026-12-20") LocalDate originDepartureDate,
        @Schema(type = "string", example = "SL") TravelClass travelClass,
        @Schema(example = "Sleeper") String travelClassName,
        @Schema(example = "NON_AC") TatkalCategory tatkalCategory,
        @Schema(example = "2026-12-19") LocalDate bookingDate,
        @Schema(type = "string", format = "time", example = "11:00:00") LocalTime bookingTime,
        @Schema(example = "Asia/Kolkata") String timeZone,
        @Schema(example = "2026-12-19T11:00:00+05:30") OffsetDateTime bookingDateTime,
        @Schema(example = "NOT_OPEN") BookingStatus status,
        @Schema(example = "7095600000") Long millisUntilOpening,
        @Schema(example = "2026-09-28T08:00:00+05:30") OffsetDateTime calculatedAt,
        @Schema(description = "Explanation when no standard Tatkal time applies") String message,
        DisplayValues display,
        List<String> notes) {
}
