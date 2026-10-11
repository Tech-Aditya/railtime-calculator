package com.example.railtime.model.request;

import com.example.railtime.model.TicketStatus;
import com.example.railtime.model.TrainType;
import com.example.railtime.model.TravelClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Input for the cancellation refund estimate. All times are IST.
 *
 * @param totalFare         ticket fare for all passengers: IRCTC's "Booking Amount", excluding the
 *                          convenience fee and travel insurance (those are never part of the fare refund)
 * @param cancellationDate  optional; defaults to now
 * @param cancellationTime  optional; defaults to now
 * @param trainType         optional; defaults to {@link TrainType#STANDARD}
 */
@Schema(description = "Cancellation refund estimate request (times in IST)")
public record CancellationRequest(

        @Schema(type = "string", example = "3A", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Travel class is required")
        TravelClass travelClass,

        @Schema(example = "CONFIRMED", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Ticket status is required")
        TicketStatus ticketStatus,

        @Schema(description = "Ticket fare for all passengers, in rupees: the \"Booking Amount\" shown by IRCTC. "
                + "Exclude the convenience fee and travel insurance, which are not part of the fare refund.",
                example = "2450.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Fare is required")
        @DecimalMin(value = "1", message = "Fare must be at least ₹1")
        @DecimalMax(value = "1000000", message = "Fare must not exceed ₹10,00,000")
        @Digits(integer = 7, fraction = 2, message = "Fare can have at most 2 decimal places")
        BigDecimal totalFare,

        @Schema(example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(value = 1, message = "At least 1 passenger is required")
        @Max(value = 6, message = "A ticket can have at most 6 passengers")
        int passengers,

        @Schema(description = "Scheduled departure date", example = "2026-12-20", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Departure date is required")
        LocalDate departureDate,

        @Schema(type = "string", format = "time", description = "Scheduled departure time (IST)", example = "18:30",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Departure time is required")
        LocalTime departureTime,

        @Schema(description = "When you plan to cancel; defaults to now", example = "2026-12-18")
        LocalDate cancellationDate,

        @Schema(type = "string", format = "time", description = "When you plan to cancel (IST); defaults to now", example = "10:00")
        LocalTime cancellationTime,

        @Schema(description = "Train type; Vande Bharat Sleeper and Amrit Bharat II have their own refund scale. "
                + "Defaults to STANDARD.", example = "STANDARD")
        TrainType trainType) {

    public CancellationRequest {
        trainType = trainType == null ? TrainType.STANDARD : trainType;
    }

    /** A standard-train request. */
    public CancellationRequest(TravelClass travelClass, TicketStatus ticketStatus, BigDecimal totalFare, int passengers,
                               LocalDate departureDate, LocalTime departureTime,
                               LocalDate cancellationDate, LocalTime cancellationTime) {
        this(travelClass, ticketStatus, totalFare, passengers, departureDate, departureTime,
                cancellationDate, cancellationTime, TrainType.STANDARD);
    }
}
