package com.example.railtime.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly typed, validated IRCTC booking-rule configuration (prefix {@code booking}).
 *
 * <p>Every rule parameter lives here so that a future IRCTC rule change (for example a new
 * Advance Reservation Period) is a configuration change, not a code change. The application
 * fails fast at startup if any value is missing or out of range.
 *
 * @param timezone   zone used for every booking date/time (Indian Standard Time)
 * @param general    general (ARP) reservation rules
 * @param tatkal     Tatkal rules
 * @param validation accepted journey-date window for API requests
 */
@Validated
@ConfigurationProperties(prefix = "booking")
public record BookingRulesProperties(
        @NotNull ZoneId timezone,
        @NotNull @Valid General general,
        @NotNull @Valid Tatkal tatkal,
        @NotNull @Valid Validation validation,
        @NotNull @Valid Cancellation cancellation,
        @NotNull @Valid Chart chart) {

    /**
     * @param advanceDays Advance Reservation Period in days, excluding the date of journey
     * @param openingTime time (IST) at which online booking starts on the opening day
     */
    public record General(
            @Min(1) @Max(365) int advanceDays,
            @NotNull LocalTime openingTime) {
    }

    /**
     * @param daysBeforeDeparture how many days before the origin departure date Tatkal opens
     *                            (the journey date itself excluded)
     * @param acOpeningTime       opening time (IST) for AC Tatkal classes
     * @param nonAcOpeningTime    opening time (IST) for non-AC Tatkal classes
     * @param agentBlackoutMinutes authorised agents may not book in this many first minutes of the window
     */
    public record Tatkal(
            @Min(1) @Max(7) int daysBeforeDeparture,
            @NotNull LocalTime acOpeningTime,
            @NotNull LocalTime nonAcOpeningTime,
            @Min(0) @Max(120) int agentBlackoutMinutes) {
    }

    /**
     * @param maxDaysInPast   oldest journey date (relative to today, IST) the API accepts
     * @param maxDaysInFuture furthest journey date (relative to today, IST) the API accepts
     */
    public record Validation(
            @Min(0) @Max(3650) int maxDaysInPast,
            @Min(1) @Max(3650) int maxDaysInFuture) {
    }

    /**
     * Cancellation charges per the Railway Passengers (Cancellation of Ticket and Refund of Fare)
     * Rules, 2015, as amended in 2026. All hour limits are measured before the scheduled departure.
     *
     * <p>Standard trains use {@code current} for cancellations on or after {@code revisedFrom}
     * (72 h / 24 h / 8 h) and {@code previous} before it (the original 48 h / 12 h / 4 h).
     * Vande Bharat Sleeper and Amrit Bharat II use {@code premiumSleeper}.
     *
     * @param flatCharges              minimum charge per passenger for a confirmed ticket, keyed by class code
     * @param clerkage                 charge per passenger for cancelling an RAC or waitlisted ticket
     * @param gstRatePercent           GST levied on the cancellation charge / clerkage for {@code gstClasses}
     * @param gstClasses               class codes on which GST applies (AC classes and First Class)
     * @param quarterRatePercent       percentage of fare in the first percentage slab (25)
     * @param halfRatePercent          percentage of fare in the second percentage slab (50)
     * @param racWaitlistCutoffMinutes RAC / waitlisted tickets: no refund within this many minutes (30)
     * @param revisedFrom              first cancellation date (IST) on which {@code current} applies
     * @param current                  standard-train slabs from {@code revisedFrom}
     * @param previous                 standard-train slabs before {@code revisedFrom}
     * @param premiumSleeper           Vande Bharat Sleeper / Amrit Bharat II scale
     */
    public record Cancellation(
            @NotEmpty Map<String, BigDecimal> flatCharges,
            @NotNull @PositiveOrZero BigDecimal clerkage,
            @NotNull @PositiveOrZero BigDecimal gstRatePercent,
            @NotNull Set<String> gstClasses,
            @NotNull @Positive BigDecimal quarterRatePercent,
            @NotNull @Positive BigDecimal halfRatePercent,
            @Min(0) int racWaitlistCutoffMinutes,
            @NotNull LocalDate revisedFrom,
            @NotNull @Valid Slabs current,
            @NotNull @Valid Slabs previous,
            @NotNull @Valid PremiumSleeper premiumSleeper) {
    }

    /**
     * Standard-train time slabs for confirmed tickets.
     *
     * @param flatChargeAboveHours   more than this many hours before departure: flat charge only
     * @param quarterChargeFromHours from this many hours up to the above: 25% slab
     * @param confirmedCutoffHours   from this many hours up to the above: 50% slab; within it, no refund
     */
    public record Slabs(
            @Min(1) int flatChargeAboveHours,
            @Min(1) int quarterChargeFromHours,
            @Min(0) int confirmedCutoffHours) {
    }

    /**
     * Refund scale for Vande Bharat Sleeper and Amrit Bharat II (G.S.R. 41(E), 16 January 2026):
     * a percentage of fare only, with no flat minimum.
     *
     * @param earlyAboveHours   more than this many hours before departure: {@code earlyRatePercent} (72)
     * @param earlyRatePercent  charge as a percentage of fare in the early slab (25)
     * @param lateRatePercent   charge from {@code cutoffHours} up to {@code earlyAboveHours} (50)
     * @param cutoffHours       within this many hours of departure: no refund (8)
     */
    public record PremiumSleeper(
            @Min(1) int earlyAboveHours,
            @NotNull @Positive BigDecimal earlyRatePercent,
            @NotNull @Positive BigDecimal lateRatePercent,
            @Min(0) int cutoffHours) {
    }

    /**
     * Reservation chart timings per the Railway Board letter of 16 December 2025.
     *
     * @param firstChartHoursBefore   first chart is prepared this many hours before departure (10)
     * @param previousDayBandStart    departures from this time (inclusive, 05:01) …
     * @param previousDayBandEnd      … up to this time (inclusive, 14:00) get the chart the previous evening
     * @param previousDayChartTime    time of that previous-day chart (20:00)
     * @param finalChartMinutesBefore final chart is prepared this many minutes before departure (30)
     * @param effectiveFrom           date from which these timings apply
     */
    public record Chart(
            @Min(1) @Max(48) int firstChartHoursBefore,
            @NotNull LocalTime previousDayBandStart,
            @NotNull LocalTime previousDayBandEnd,
            @NotNull LocalTime previousDayChartTime,
            @Min(1) @Max(240) int finalChartMinutesBefore,
            @NotNull LocalDate effectiveFrom) {
    }
}
