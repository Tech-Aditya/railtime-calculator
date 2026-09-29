package com.example.railtime.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Input for the reservation chart time estimate.
 *
 * @param departureDate date the train departs the station the chart is prepared for
 * @param departureTime scheduled departure time there (IST)
 */
@Schema(description = "Chart preparation time request (IST)")
public record ChartRequest(

        @Schema(example = "2026-12-20", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Departure date is required")
        LocalDate departureDate,

        @Schema(type = "string", format = "time", example = "10:30", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Departure time is required")
        LocalTime departureTime) {
}
