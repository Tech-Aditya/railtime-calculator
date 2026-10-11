package com.example.railtime.model.response;

import com.example.railtime.model.TicketStatus;
import com.example.railtime.model.TrainType;
import com.example.railtime.model.TravelClass;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Estimated refund on cancellation. Amounts are in rupees; per-passenger values are multiplied by
 * {@code passengers} for the totals.
 *
 * @param ruleSet            which refund scale applied (depends on train type and cancellation date)
 * @param slab               which rule of that scale applied
 * @param minutesBeforeDeparture time between cancellation and scheduled departure
 * @param refundable         whether any refund is due
 */
@Schema(description = "Cancellation refund estimate")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CancellationResponse(
        @Schema(type = "string", example = "3A") TravelClass travelClass,
        @Schema(example = "Third AC") String travelClassName,
        @Schema(example = "CONFIRMED") TicketStatus ticketStatus,
        @Schema(example = "STANDARD") TrainType trainType,
        @Schema(example = "2") int passengers,
        @Schema(example = "2026-12-20T18:30:00+05:30") OffsetDateTime departureDateTime,
        @Schema(example = "2026-12-18T10:00:00+05:30") OffsetDateTime cancellationDateTime,
        @Schema(example = "3390") long minutesBeforeDeparture,
        @Schema(example = "STANDARD_2026") RefundRuleSet ruleSet,
        @Schema(example = "QUARTER_OF_FARE") CancellationSlab slab,
        @Schema(example = "72 to 24 hours before departure: 25% of fare (minimum ₹180 per passenger)") String slabDescription,
        boolean refundable,
        @Schema(example = "2450.00") BigDecimal totalFare,
        @Schema(example = "1225.00") BigDecimal farePerPassenger,
        @Schema(example = "306.25") BigDecimal chargePerPassenger,
        @Schema(example = "15.31") BigDecimal gstPerPassenger,
        @Schema(example = "612.50") BigDecimal totalCharge,
        @Schema(example = "30.62") BigDecimal totalGst,
        @Schema(example = "1806.88") BigDecimal totalRefund,
        @Schema(example = "5") BigDecimal gstRatePercent,
        List<String> notes) {

    /** The refund scale that produced the estimate. */
    public enum RefundRuleSet {
        /** Standard trains, cancelled on or after the 2026 revision: 72 h / 24 h / 8 h. */
        STANDARD_2026,
        /** Standard trains, cancelled before the 2026 revision: the original 2015 slabs, 48 h / 12 h / 4 h. */
        STANDARD_2015,
        /** Vande Bharat Sleeper / Amrit Bharat II: 25% above 72 h, 50% down to 8 h, then nothing. */
        PREMIUM_SLEEPER_2026
    }

    /** The rule that produced the estimate. */
    public enum CancellationSlab {
        FLAT_CHARGE,
        QUARTER_OF_FARE,
        HALF_OF_FARE,
        CLERKAGE,
        NO_REFUND_CONFIRMED,
        NO_REFUND_TATKAL,
        NO_REFUND_RAC_WAITLIST
    }
}
