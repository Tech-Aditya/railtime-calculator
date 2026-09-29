package com.example.railtime.model.response;

import com.example.railtime.model.TatkalCategory;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;
import java.util.List;

/** The booking rules currently configured in this deployment. */
@Schema(description = "Currently configured booking rules")
public record BookingRulesResponse(
        @Schema(example = "Asia/Kolkata") String timeZone,
        GeneralRule general,
        TatkalRule tatkal,
        List<String> disclaimers) {

    @Schema(description = "General reservation (ARP) rule")
    public record GeneralRule(
            @Schema(example = "60") int arpDays,
            @Schema(type = "string", format = "time", example = "08:00:00") LocalTime openingTime,
            String description) {
    }

    @Schema(description = "Tatkal rule")
    public record TatkalRule(
            @Schema(example = "1") int daysBeforeDeparture,
            @Schema(type = "string", format = "time", example = "10:00:00") LocalTime acOpeningTime,
            @Schema(type = "string", format = "time", example = "11:00:00") LocalTime nonAcOpeningTime,
            List<TravelClassRule> classes) {
    }

    @Schema(description = "Tatkal treatment of one travel class")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TravelClassRule(
            @Schema(example = "SL") String code,
            @Schema(example = "Sleeper") String name,
            @Schema(example = "NON_AC") TatkalCategory category,
            @Schema(type = "string", format = "time", example = "11:00:00",
                    description = "Absent for classes not listed under standard Tatkal")
            LocalTime openingTime) {
    }
}
