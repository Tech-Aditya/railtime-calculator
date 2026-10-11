package com.example.railtime;

import com.example.railtime.properties.BookingRulesProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Shared deterministic fixtures for tests. */
public final class TestFixtures {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** 28 September 2026, 08:00:00 IST. */
    public static final Instant NOW = LocalDateTime.of(2026, 9, 28, 8, 0).atZone(IST).toInstant();

    private TestFixtures() {
    }

    public static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /** A fixed clock at the given IST wall-clock time. */
    public static Clock clockAtIst(LocalDateTime istDateTime) {
        return Clock.fixed(istDateTime.atZone(IST).toInstant(), ZoneOffset.UTC);
    }

    /** The production default rules: 60-day ARP at 08:00; Tatkal 1 day before at 10:00 / 11:00. */
    public static BookingRulesProperties defaultRules() {
        return rulesWithArp(60);
    }

    public static BookingRulesProperties rulesWithArp(int arpDays) {
        return new BookingRulesProperties(
                IST,
                new BookingRulesProperties.General(arpDays, LocalTime.of(8, 0)),
                new BookingRulesProperties.Tatkal(1, LocalTime.of(10, 0), LocalTime.of(11, 0), 30),
                new BookingRulesProperties.Validation(365, 730),
                cancellationRules(),
                chartRules());
    }

    /** Cancellations on or after this date use the 2026 slabs (72 / 24 / 8 h). */
    public static final LocalDate REFUND_RULES_REVISED_FROM = LocalDate.of(2026, 4, 1);

    /**
     * The cancellation rules as amended in 2026: flat charges, 25% / 50% slabs (72 / 24 / 8 h; the original
     * 48 / 12 / 4 h before 1 Apr 2026), ₹60 clerkage, 5% GST on AC / FC, and the premium-sleeper scale.
     */
    public static BookingRulesProperties.Cancellation cancellationRules() {
        return cancellationRules(flatCharges());
    }

    public static BookingRulesProperties.Cancellation cancellationRules(Map<String, BigDecimal> flatCharges) {
        return new BookingRulesProperties.Cancellation(flatCharges, new BigDecimal("60"), new BigDecimal("5"),
                Set.of("1A", "EC", "2A", "FC", "3A", "CC", "3E"), new BigDecimal("25"), new BigDecimal("50"), 30,
                REFUND_RULES_REVISED_FROM,
                new BookingRulesProperties.Slabs(72, 24, 8),
                new BookingRulesProperties.Slabs(48, 12, 4),
                new BookingRulesProperties.PremiumSleeper(72, new BigDecimal("25"), new BigDecimal("50"), 8));
    }

    public static Map<String, BigDecimal> flatCharges() {
        Map<String, BigDecimal> flat = new LinkedHashMap<>();
        flat.put("1A", new BigDecimal("240"));
        flat.put("EC", new BigDecimal("240"));
        flat.put("2A", new BigDecimal("200"));
        flat.put("FC", new BigDecimal("200"));
        flat.put("3A", new BigDecimal("180"));
        flat.put("CC", new BigDecimal("180"));
        flat.put("3E", new BigDecimal("180"));
        flat.put("SL", new BigDecimal("120"));
        flat.put("2S", new BigDecimal("60"));
        return flat;
    }

    /** Charting per the Railway Board letter of 16 December 2025. */
    public static BookingRulesProperties.Chart chartRules() {
        return new BookingRulesProperties.Chart(10, LocalTime.of(5, 1), LocalTime.of(14, 0), LocalTime.of(20, 0), 30,
                LocalDate.of(2025, 12, 16));
    }
}
