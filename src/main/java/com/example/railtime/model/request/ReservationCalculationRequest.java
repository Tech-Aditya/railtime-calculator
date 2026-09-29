package com.example.railtime.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Input for the general (ARP) reservation calculator.
 *
 * @param originDepartureDate date the train departs its <em>originating</em> station
 * @param boardingDate        optional date the passenger boards; informational only, because the
 *                            opening date is always derived from the origin departure date
 */
@Schema(description = "General reservation calculation request")
public record ReservationCalculationRequest(

        @Schema(description = "Date the train departs its originating station (ISO-8601, yyyy-MM-dd)",
                example = "2026-12-20", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Origin departure date is required")
        LocalDate originDepartureDate,

        @Schema(description = "Optional passenger boarding date, if boarding at an intermediate station. "
                + "It never changes the calculated opening date; it only adds guidance.",
                example = "2026-12-21", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LocalDate boardingDate) {
}
