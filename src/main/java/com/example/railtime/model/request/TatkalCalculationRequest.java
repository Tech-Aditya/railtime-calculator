package com.example.railtime.model.request;

import com.example.railtime.model.TravelClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Input for the Tatkal calculator.
 *
 * @param originDepartureDate date the train departs its originating station
 * @param travelClass         IRCTC class code, for example {@code SL} or {@code 3A}
 */
@Schema(description = "Tatkal calculation request")
public record TatkalCalculationRequest(

        @Schema(description = "Date the train departs its originating station (ISO-8601, yyyy-MM-dd)",
                example = "2026-12-20", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Origin departure date is required")
        LocalDate originDepartureDate,

        @Schema(description = "IRCTC travel class code (case-insensitive)", example = "SL",
                type = "string", allowableValues = {"SL", "1A", "2A", "3A", "CC", "EC", "3E", "FC", "2S"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Travel class is required")
        TravelClass travelClass) {
}
